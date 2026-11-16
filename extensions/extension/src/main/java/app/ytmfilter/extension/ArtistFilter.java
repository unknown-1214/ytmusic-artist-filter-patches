package app.ytmfilter.extension;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 再生キューへ足される曲のうち、自動ミックス(再生リスト ID が RDAMVM / RDAMPL で始まる曲)だけを、
 * 許可アーティストに絞る。それ以外(アルバム OLAK…・自分のプレイリスト PL…・高評価 LM・次に再生 QP…・
 * 検索などで自分が選んだ曲 = ID が空)は一切触らない。
 *
 * 曲の型は難読化されているので、引数なしで String を返すメソッド g() (アーティスト欄) と
 * r() (再生リスト ID) をリフレクションで呼ぶ。見つからない・例外のときは、元の集まりをそのまま返す。
 *
 * 入口は 2 つ:
 *  - filter(): キューへ曲を足す共通の入口。位置に依存しない足し方 (続きの取得・自動で足される側) だけを絞る
 *  - trimNewQueue(): 新しいキューを作る関数の先頭。曲をタップして作られるミックスは、選んだ曲と一緒に 1 回で位置 0 に入るので、
 *    ここで「再生位置までは残し、後ろだけ」絞る (filter() では位置を壊すので絞れない)
 *
 * filter() で絞らない場合 (呼び出し元が位置・個数を前提にしているため、絞るとキューが壊れる):
 *  - listIndex == 0 かつ pos <= 0 : キューの復元・新しい一覧の設定 (U(max) で一覧の max 番目を再生位置にする)
 *  - 曲が 1 曲以下 : 再生中の曲の入れ直し・「次に再生」など、利用者操作の結果。挿入に成功した前提で位置を数える経路がある
 */
@SuppressWarnings("unused")
public class ArtistFilter {
    private static final String TAG = "YTMArtistFilter";

    /** パッチ実行時に、オプションの値 (UTF-8 を Base64 にしたもの) を返す実装へ差し替えられる。 */
    public static String allowedArtistsBase64() {
        return "";
    }

    static String allowedArtists() {
        try {
            String b64 = allowedArtistsBase64();
            if (b64 == null || b64.isEmpty()) return "";
            return new String(java.util.Base64.getDecoder().decode(b64), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.w(TAG, "allowed artists option could not be decoded: " + t);
            return "";
        }
    }

    // ---- 許可リスト --------------------------------------------------------------

    private static volatile Set<String> allowed;

    private static Set<String> allowed() {
        Set<String> a = allowed;
        if (a == null) {
            a = parseAllowed(allowedArtists());
            allowed = a;
        }
        return a;
    }

    /** オプション文字列を許可名の集合にする。区切りは , ， 、 ; ； と改行。正規化して持つ。 */
    static Set<String> parseAllowed(String raw) {
        Set<String> set = new HashSet<>();
        if (raw == null) return set;
        for (String s : raw.split("[,，、;；\\r\\n]+")) {
            String t = normalize(s);
            if (!t.isEmpty()) set.add(t);
        }
        return set;
    }

    // ---- 照合 -------------------------------------------------------------------

    /** 全角/半角・大文字小文字・ゼロ幅文字・連続空白の違いを吸収する (NFKC + 小文字 + 空白の整理)。 */
    static String normalize(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFKC);
        t = t.replaceAll("[\\u200B-\\u200D\\u2060\\uFEFF]", "");
        t = t.replaceAll("\\s+", " ").trim();
        return t.toLowerCase(Locale.ROOT);
    }

    /** 投稿者名 (Topic チャンネルや公式チャンネル) に付く定型の接尾辞を外す。 */
    private static final String[] SUFFIXES = {
            " - topic", " topic", " official youtube channel", " official channel", " official",
            " vevo", "公式チャンネル", " 公式チャンネル", "公式", " 公式"
    };

    static String stripSuffix(String n) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String suf : SUFFIXES) {
                if (n.length() > suf.length() && n.endsWith(suf)) {
                    n = n.substring(0, n.length() - suf.length()).trim();
                    changed = true;
                }
            }
        }
        return n;
    }

    /** アーティスト欄を 、 , ， & ＆ × / ; と " feat. " で分け、どれかが許可名と一致すれば通す。欄の全体でも照合する。 */
    static boolean matches(String artistField, Set<String> ok) {
        String whole = normalize(artistField);
        if (ok.contains(whole) || ok.contains(stripSuffix(whole))) return true;
        for (String part : whole.split("[、,，&＆×/;；]| feat\\. | ft\\. ")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (ok.contains(p) || ok.contains(stripSuffix(p))) return true;
        }
        return false;
    }

    static boolean isMix(String playlistId) {
        return playlistId != null && (playlistId.startsWith("RDAMVM") || playlistId.startsWith("RDAMPL"));
    }

    // ---- 本体 -------------------------------------------------------------------

    /**
     * @param listIndex 足し先のリスト (0 = 再生キュー本体 / 1 = 自動で足される側)
     * @param pos       足す位置
     */
    public static Collection<?> filter(int listIndex, int pos, Collection<?> items) {
        try {
            if (items == null || items.isEmpty()) return items;
            int n = items.size();
            if (n <= 1) { // 1 曲だけの追加は、利用者の操作の結果。絞らない
                return items;
            }
            if (listIndex != 1 && pos <= 0) {
                Log.i(TAG, "queue add list=" + listIndex + " pos=" + pos + " count=" + n + " -> skip (index-sensitive)");
                return items;
            }
            Set<String> ok = allowed();
            if (ok.isEmpty()) return items; // 許可リストが空なら絞らない

            ArrayList<Object> kept = keepAllowed(items, ok);
            int dropped = n - kept.size();
            Log.i(TAG, "queue add list=" + listIndex + " pos=" + pos + " count=" + n
                    + (dropped > 0 ? " -> mix filtered: kept=" + kept.size() + " dropped=" + dropped : " -> nothing dropped"));
            return dropped == 0 ? items : kept;
        } catch (Throwable t) {
            Log.w(TAG, "filter failed, passing through: " + t);
            return items;
        }
    }

    /**
     * 新しい再生キューの一覧 (曲の List・付帯情報・再生位置 int を持つ難読化クラス) を、その場で絞る。
     * 再生位置 (選んだ曲) とそれより前は 1 曲も変えず、後ろのミックスの曲だけを許可アーティストに絞る。
     * 呼び出し元は「一覧の (再生位置) 番目」を再生するので、後ろを減らしても再生する曲はずれない。
     * 一覧の形が想定と違う・List が ArrayList でない・例外のときは、何も変えない。
     */
    public static void trimNewQueue(Object spec) {
        try {
            if (spec == null) return;
            Field[] f = specFields(spec.getClass()); // [0]=曲の List [1]=再生位置
            if (f == null) return;
            Object value = f[0].get(spec);
            if (!(value instanceof ArrayList)) {
                logOnce("spec-list", "new queue list is " + (value == null ? "null" : value.getClass().getName())
                        + ", not ArrayList -> not touched");
                return;
            }
            @SuppressWarnings("unchecked")
            ArrayList<Object> list = (ArrayList<Object>) value;
            int start = Math.max(0, f[1].getInt(spec)); // 呼び出し元と同じく、負の値は 0 番目
            int n = list.size();
            if (start + 1 >= n) return; // 再生位置より後ろに曲が無い
            Set<String> ok = allowed();
            if (ok.isEmpty()) return;

            List<Object> tail = list.subList(start + 1, n);
            ArrayList<Object> keptTail = keepAllowed(tail, ok);
            int dropped = tail.size() - keptTail.size();
            Log.i(TAG, "new queue start=" + start + " count=" + n + (dropped > 0
                    ? " -> mix filtered after start: kept=" + (start + 1 + keptTail.size()) + " dropped=" + dropped
                    : " -> nothing dropped"));
            if (dropped == 0) return;
            tail.clear(); // ArrayList の部分リストなので、元の一覧の start+1 以降が消える
            list.addAll(keptTail);
        } catch (Throwable t) {
            Log.w(TAG, "trimNewQueue failed, queue left as is: " + t);
        }
    }

    /** 曲の集まりから、ミックスの許可外の曲を除いた新しいリストを返す。ミックス以外・判定できない曲は残す。 */
    private static ArrayList<Object> keepAllowed(Collection<?> items, Set<String> ok) {
        ArrayList<Object> kept = new ArrayList<>(items.size());
        Class<?> cls = null;
        Method[] gr = null; // [0]=g()(アーティスト欄) [1]=r()(再生リスト ID)
        for (Object item : items) {
            if (item == null) { kept.add(null); continue; }
            if (item.getClass() != cls) { // 型が変わったときだけ探し直す
                cls = item.getClass();
                gr = lookup(cls);
            }
            if (gr == null) { kept.add(item); continue; } // 見つからなければ触らない
            String playlistId = str(gr[1], item);
            noteUnknownRd(playlistId);
            if (!isMix(playlistId)) { kept.add(item); continue; } // ミックス以外は常に残す
            String artist = str(gr[0], item);
            if (artist == null || artist.trim().isEmpty() || matches(artist, ok)) kept.add(item);
        }
        return kept;
    }

    /** 一覧のクラスから「List 型・int 型の、static でないフィールド」をそれぞれちょうど 1 つ探す。無ければ null (1 回ログ)。 */
    private static final Field[] NO_FIELDS = new Field[0];
    private static final ConcurrentHashMap<Class<?>, Field[]> SPEC_CACHE = new ConcurrentHashMap<>();

    private static Field[] specFields(Class<?> cls) {
        Field[] cached = SPEC_CACHE.get(cls);
        if (cached != null) return cached == NO_FIELDS ? null : cached;
        Field listField = null, intField = null;
        int lists = 0, ints = 0;
        for (Field fd : cls.getDeclaredFields()) {
            if (Modifier.isStatic(fd.getModifiers())) continue;
            if (fd.getType() == List.class) { listField = fd; lists++; }
            else if (fd.getType() == int.class) { intField = fd; ints++; }
        }
        Field[] res = NO_FIELDS;
        if (lists == 1 && ints == 1) {
            try {
                listField.setAccessible(true);
                intField.setAccessible(true);
                res = new Field[]{listField, intField};
            } catch (Throwable t) {
                Log.w(TAG, "cannot access new queue fields on " + cls.getName() + ": " + t);
            }
        }
        SPEC_CACHE.put(cls, res);
        if (res == NO_FIELDS) {
            logOnce("spec:" + cls.getName(), "new queue spec " + cls.getName() + " has " + lists + " List / "
                    + ints + " int fields -> not touched");
        }
        return res == NO_FIELDS ? null : res;
    }

    private static void logOnce(String key, String message) {
        if (LOGGED.size() < 32 && LOGGED.putIfAbsent(key, true) == null) Log.i(TAG, message);
    }

    // ---- リフレクション ------------------------------------------------------------

    /** 型ごとの g()/r() の探索結果。見つからない型は NONE を入れて、毎回探し直さない。 */
    private static final Method[] NONE = new Method[0];
    private static final ConcurrentHashMap<Class<?>, Method[]> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> LOGGED = new ConcurrentHashMap<>();

    private static Method[] lookup(Class<?> cls) {
        Method[] cached = CACHE.get(cls);
        if (cached != null) return cached == NONE ? null : cached;
        Method g = find(cls, "g");
        Method r = find(cls, "r");
        Method[] res = (g != null && r != null) ? new Method[]{g, r} : NONE;
        CACHE.put(cls, res);
        if (res == NONE && LOGGED.size() < 8 && LOGGED.putIfAbsent("cls:" + cls.getName(), true) == null) {
            Log.i(TAG, "no g()/r() on " + cls.getName() + " (g=" + (g != null) + " r=" + (r != null) + ") -> not touched");
        }
        return res == NONE ? null : res;
    }

    /** 引数なしで String を返す、指定名のメソッド。非公開のクラス・メソッドでも、親クラスをたどって探し、呼べるようにする。 */
    private static Method find(Class<?> cls, String name) {
        try {
            try {
                Method m = cls.getMethod(name);
                if (m.getReturnType() == String.class) { m.setAccessible(true); return m; }
            } catch (NoSuchMethodException ignored) {
                // 公開メソッドに無ければ、宣言されているものを探す
            }
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod(name);
                    if (m.getReturnType() == String.class && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                        m.setAccessible(true);
                        return m;
                    }
                } catch (NoSuchMethodException ignored) {
                    // 次の親クラスへ
                }
            }
        } catch (Throwable t) {
            // 何も見つからなかった扱い
        }
        return null;
    }

    private static String str(Method m, Object o) {
        try {
            Object v = m.invoke(o);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** RD で始まるが、ミックスとして扱っていない種類 (アーティストのラジオなど) を、型を増やす材料としてログに残す。 */
    private static void noteUnknownRd(String playlistId) {
        if (playlistId == null || !playlistId.startsWith("RD") || isMix(playlistId)) return;
        String prefix = playlistId.length() > 6 ? playlistId.substring(0, 6) : playlistId;
        if (LOGGED.size() < 24 && LOGGED.putIfAbsent("rd:" + prefix, true) == null) {
            Log.i(TAG, "unfiltered RD playlist id prefix: " + prefix + " (not RDAMVM/RDAMPL)");
        }
    }
}
