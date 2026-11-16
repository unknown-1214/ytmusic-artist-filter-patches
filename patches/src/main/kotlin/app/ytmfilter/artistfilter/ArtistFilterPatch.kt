package app.ytmfilter.artistfilter

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_YTM
import com.android.tools.smali.dexlib2.AccessFlags
import java.util.Base64
import java.util.logging.Logger

private const val EXTENSION_CLASS = "Lapp/ytmfilter/extension/ArtistFilter;"
private val logger = Logger.getLogger("ArtistFilterPatch")

@Suppress("unused")
val artistFilterPatch = patch(
    name = "Artist filter for auto mixes",
    description = "自動ミックス(曲/再生リストから作られるラジオ)に流れてくる曲を、許可したアーティストだけにする。" +
        "検索・アルバム・自分のプレイリスト・自分で選んだ曲は絞らない。",
    default = true
) {
    compatibleWith(COMPATIBILITY_YTM)

    extendWith("extensions/extension.mpe")

    val allowedArtists by stringOption(
        key = "allowedArtists",
        title = "Allowed artists",
        default = "RADWIMPS,野田洋次郎,米津玄師,Creepy Nuts,サカナクション",
        description = "ミックスに流してよいアーティスト名。カンマ(, または 、)区切り。" +
            "曲のアーティスト欄に、この名前のどれかが 1 つでも入っていれば通す(共作は 、 区切りで照合)。" +
            "表記は YT Music の画面に出るとおりに書く。",
        required = true,
    )

    execute {
        // 拡張のダミー関数 allowedArtistsBase64() を、オプションの値 (UTF-8 → Base64) を返すものに差し替える。
        // 値を smali の文字列にそのまま埋めると、引用符・円記号・改行でパッチが失敗するので、Base64 にして渡し、拡張側で復号する。
        val encoded = Base64.getEncoder().encodeToString((allowedArtists ?: "").toByteArray(Charsets.UTF_8))
        mutableClassDefBy(EXTENSION_CLASS).methods
            .first { it.name == "allowedArtistsBase64" }
            .returnEarly(encoded)

        // 差し込む前に、当たったメソッドが期待どおりかを自分で検査する。Morphe は指紋が複数のメソッドに当たっても
        // エラーにせず最初の 1 つへ黙って差し込むので、将来の版で同じ形のメソッドが増えても、別の場所へ入れないための安全弁。
        val target = QueueAddFingerprint.method
        if (QueueAddFingerprint.instructionMatches.size != 4) {
            throw PatchException("Queue add fingerprint matched an unexpected method: expected 4 instruction matches, got " +
                QueueAddFingerprint.instructionMatches.size)
        }
        val hasSparseArrayField = QueueAddFingerprint.originalClassDef.fields.any { it.type == "Landroid/util/SparseArray;" }
        if (!hasSparseArrayField) {
            throw PatchException("Queue add fingerprint matched a class without a SparseArray field (${target.definingClass}); " +
                "refusing to patch the wrong method.")
        }

        // p0=this, p1=リスト番号, p2=足す位置, p3=曲の集まり。
        // 位置に依存しない足し方のときだけ、ミックスの曲を許可アーティストに絞った集まりへ差し替える(判定は拡張側)。
        target.addInstructions(
            0,
            """
                invoke-static { p1, p2, p3 }, $EXTENSION_CLASS->filter(IILjava/util/Collection;)Ljava/util/Collection;
                move-result-object p3
            """
        )

        hookNewQueue()
    }
}

/**
 * 新しいキューを作る関数の先頭で、一覧のうち再生位置より後ろのミックスだけを絞る (拡張の trimNewQueue)。
 * 曲をタップして作られるミックスは、選んだ曲と一緒に 1 回で位置 0 へ入るので、入口の側では絞れない (v4 の取りこぼし)。
 * 将来の版で見つからない・形が違うときは、この差し込みだけを飛ばす (入口の絞り込みは残る。パッチ全体は失敗させない)。
 */
private fun hookNewQueue() {
    val matches = NewQueueFingerprint.matchAllOrNull()
    val match = matches?.singleOrNull()
    if (match == null) {
        logger.warning("New queue method not found uniquely (matches=${matches?.size ?: 0}); skipping the new-queue filter.")
        return
    }
    // 2 番目の引数 (一覧) の型が「List を 1 つ・int を 1 つ」持つクラスであることを確かめる (拡張はこの 2 つを読む)。
    val specType = match.originalMethod.parameterTypes[1].toString()
    val fields = classDefByOrNull(specType)?.fields?.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.orEmpty()
    val listFields = fields.count { it.type == "Ljava/util/List;" }
    val intFields = fields.count { it.type == "I" }
    if (listFields != 1 || intFields != 1) {
        logger.warning("New queue spec $specType has $listFields List / $intFields int fields; skipping the new-queue filter.")
        return
    }
    // p0=this, p1=通信の応答, p2=一覧, p3=呼び出し元の情報。先頭で一覧の List をその場で絞る。
    match.method.addInstructions(
        0,
        "invoke-static { p2 }, $EXTENSION_CLASS->trimNewQueue(Ljava/lang/Object;)V"
    )
}
