package app.ytmfilter.artistfilter

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

/**
 * 再生キューへ曲を足す共通の入口。
 *   9.30.53: baie.fZ(int, int, Collection)  /  9.20.53: azjh.fx(int, int, Collection)
 * 名前は難読化で変わるので、形で見つける:
 *   void X(int index, int pos, Collection items)、
 *   items が null/空なら戻る → items.size() → SparseArray(キュー本体と自動で足される側の 2 本のリスト).get(index) の
 *   List に addAll(pos, items)
 * 2 版とも、この形のクラスは 1 つだけ(jadx で確認)。SparseArray.get を足して、他のクラスに当たりにくくしている。
 */
object QueueAddFingerprint : Fingerprint(
    returnType = "V",
    isStatic = false,
    parameters = listOf("I", "I", "Ljava/util/Collection;"),
    filters = listOf(
        methodCall(smali = "Ljava/util/Collection;->isEmpty()Z"),
        methodCall(smali = "Ljava/util/Collection;->size()I"),
        methodCall(smali = "Landroid/util/SparseArray;->get(I)Ljava/lang/Object;"),
        methodCall(smali = "Ljava/util/List;->addAll(ILjava/util/Collection;)Z"),
    )
)

/**
 * 新しい再生キューを作る関数 (曲をタップしたとき・ミックスを始めたとき)。
 *   9.20.53: nyp.A(bynw, nyh 一覧, azjs)
 * 一覧 (nyh) は「曲の List・付帯情報・再生位置 int」だけを持つ。キューが空なら一覧を丸ごと位置 0 に入れてから
 * 「一覧の (再生位置) 番目」を再生するので、入口 X(int,int,Collection) の側では位置を壊さずに絞れない。
 * ここで「再生位置までの曲は全部残し、後ろのミックスだけ絞る」。
 * 見つけ方: この関数の中にだけ埋め込まれている、ログの文字列 (maybeUpdateCurrentPlaybackItem のもの)。
 */
object NewQueueFingerprint : Fingerprint(
    returnType = "V",
    isStatic = false,
    parameters = listOf("L", "L", "L"),
    filters = listOf(
        string("Current playback item does not match the new playback item", StringComparisonType.STARTS_WITH),
    )
)
