# YTM Artist Filter (Morphe patches)

YouTube Music の「自動ミックス」(1 曲を流したときや、再生リストから作られるラジオ) に流れてくる曲を、
許可したアーティストの曲だけに絞る Morphe パッチです。

- 絞るのは自動ミックス (再生リスト ID が `RDAMVM` / `RDAMPL` のもの) だけ。検索・アルバム・自分のプレイリスト・高く評価した曲・自分で選んだ曲は絞りません
- 選んだ曲はそのまま再生され、その後ろに付くミックスだけが絞られます
- 許可するアーティストはパッチのオプション `Allowed artists` (カンマか読点区切り) で指定します
- 対象: YouTube Music 9.20.53 / 9.30.53。Morphe Patches の `GmsCore support` などと一緒に当ててください

## 使い方

Morphe Manager にこのリポジトリを追加します。

<a href="https://morphe.software/add-source?github=unknown-1214/ytmusic-artist-filter-patches&name=YTM%20Artist%20Filter&app=com.google.android.apps.youtube.music">
  <img src=".github/assets/add-to-morphe.svg" alt="Add to Morphe" height="48">
</a>

または `https://github.com/unknown-1214/ytmusic-artist-filter-patches` を Sources の「+」→ Remote に貼ります。

## Patches

| Patch | Description |
| --- | --- |
| Artist filter for auto mixes | 自動ミックスに流れてくる曲を、許可したアーティストだけにする |

## License

GPLv3 (Morphe patches template を元にしています)
