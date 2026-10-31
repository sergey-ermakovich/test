<?php
/**
 * Tiny two-way file exchange for moving files between the phone and the car
 * head unit through shared hosting — no FTP, no WebDAV, no common Wi-Fi.
 * Both devices just open this page in a browser: upload from one, download on
 * the other. The car's browser downloads fine even when its file manager won't.
 *
 * Deploy: put this file on any PHP hosting next to a writable "exchange/" folder
 * (it is created automatically if the parent is writable). Change TOKEN below.
 *
 * Use:
 *   https://your-host/zeekr/exchange.php?token=СЕКРЕТ          — the page (list + upload)
 *   it also serves ?dl=<name> (download) and ?del=<name> (delete) with the token.
 *
 * Note on size: large uploads are limited by the host's upload_max_filesize and
 * post_max_size (php.ini / .htaccess), not by this script.
 */

const TOKEN = 'change-me';                 // <-- set your own secret
$DIR = __DIR__ . '/exchange';              // storage folder

// --- auth -------------------------------------------------------------------
$token = $_GET['token'] ?? ($_POST['token'] ?? '');
if (!hash_equals(TOKEN, (string)$token)) {
    http_response_code(403);
    exit('forbidden');
}

if (!is_dir($DIR)) @mkdir($DIR, 0775, true);

// Keep a name safe: strip any path, drop leading dots, bound length.
// (defined before the early JSON/PUT handlers below)
function safe_name(string $n): string {
    $n = basename($n);
    $n = preg_replace('/[^\w.\- ()\[\]]+/u', '_', $n);
    $n = ltrim($n, '.');
    if ($n === '') $n = 'file';
    return mb_substr($n, 0, 150);
}

function human(int $b): string {
    $u = ['Б','КБ','МБ','ГБ']; $i = 0; $v = (float)$b;
    while ($v >= 1024 && $i < 3) { $v /= 1024; $i++; }
    return ($i ? round($v, 1) : (int)$v) . ' ' . $u[$i];
}

$self = htmlspecialchars($_SERVER['PHP_SELF'] ?? 'exchange.php');
$t = rawurlencode((string)$token);

// --- JSON list (for the in-app file screen) ---------------------------------
if (isset($_GET['json'])) {
    header('Content-Type: application/json; charset=utf-8');
    $out = [];
    foreach (scandir($DIR) ?: [] as $x) {
        if ($x === '.' || $x === '..') continue;
        $p = $DIR . '/' . $x;
        if (is_file($p)) $out[] = ['name' => $x, 'size' => filesize($p), 'ts' => filemtime($p)];
    }
    usort($out, fn($a, $b) => $b['ts'] <=> $a['ts']);
    echo json_encode($out);
    exit;
}

// --- raw upload (simple for the app): POST body -> ?put=<name> ---------------
if ($_SERVER['REQUEST_METHOD'] === 'POST' && isset($_GET['put'])) {
    $name = safe_name($_GET['put']);
    $ok = file_put_contents($DIR . '/' . $name, file_get_contents('php://input')) !== false;
    http_response_code($ok ? 200 : 500);
    echo $ok ? 'ok' : 'fail';
    exit;
}

// --- download ---------------------------------------------------------------
if (isset($_GET['dl'])) {
    $name = safe_name($_GET['dl']);
    $path = $DIR . '/' . $name;
    if (is_file($path)) {
        header('Content-Type: application/octet-stream');
        header('Content-Length: ' . filesize($path));
        header('Content-Disposition: attachment; filename="' . $name . '"');
        readfile($path);
        exit;
    }
    http_response_code(404);
    exit('not found');
}

// --- delete -----------------------------------------------------------------
if (isset($_GET['del'])) {
    $path = $DIR . '/' . safe_name($_GET['del']);
    if (is_file($path)) @unlink($path);
    header('Location: ' . $self . '?token=' . $t);
    exit;
}

// --- upload -----------------------------------------------------------------
$msg = '';
if ($_SERVER['REQUEST_METHOD'] === 'POST' && !empty($_FILES['f'])) {
    $f = $_FILES['f'];
    $names = is_array($f['name']) ? $f['name'] : [$f['name']];
    $tmps  = is_array($f['tmp_name']) ? $f['tmp_name'] : [$f['tmp_name']];
    $ok = 0;
    foreach ($names as $i => $nm) {
        if (!$nm || !is_uploaded_file($tmps[$i])) continue;
        if (move_uploaded_file($tmps[$i], $DIR . '/' . safe_name($nm))) $ok++;
    }
    $msg = $ok ? "Загружено файлов: $ok" : 'Не удалось загрузить';
}

// --- page -------------------------------------------------------------------
$files = array_values(array_filter(scandir($DIR) ?: [], fn($x) => is_file($DIR . '/' . $x)));
usort($files, fn($a, $b) => filemtime($DIR . '/' . $b) <=> filemtime($DIR . '/' . $a));
header('Content-Type: text/html; charset=utf-8');
?>
<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Обменник</title>
<style>
  :root { color-scheme: light dark; --bg:#f4f6f8; --card:#fff; --line:#e2e6ec; --tx:#0f141c; --mut:#57616e; --acc:#12a594; }
  @media (prefers-color-scheme: dark) { :root { --bg:#0a0c10; --card:#14171d; --line:#282d38; --tx:#f1f3f7; --mut:#8e96a4; } }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--tx); font:16px/1.4 system-ui,-apple-system,Roboto,Segoe UI,sans-serif; padding:16px; }
  .wrap { max-width:760px; margin:0 auto; }
  h1 { font-size:20px; margin:4px 0 16px; }
  .card { background:var(--card); border:1px solid var(--line); border-radius:16px; padding:16px; margin-bottom:14px; }
  .drop { display:block; text-align:center; padding:22px; border:2px dashed var(--line); border-radius:14px; color:var(--mut); }
  input[type=file] { width:100%; margin-top:10px; }
  button, .btn { display:inline-block; border:0; border-radius:12px; padding:12px 18px; font-size:16px; font-weight:600;
                 background:var(--acc); color:#042019; text-decoration:none; cursor:pointer; }
  .row { display:flex; align-items:center; gap:12px; padding:12px 0; border-top:1px solid var(--line); }
  .row:first-child { border-top:0; }
  .row .name { flex:1; min-width:0; word-break:break-word; }
  .row .meta { color:var(--mut); font-size:13px; }
  .row a.get { background:transparent; color:var(--acc); border:1px solid var(--acc); padding:8px 14px; border-radius:10px; text-decoration:none; font-weight:600; }
  .row a.del { color:var(--mut); text-decoration:none; font-size:20px; padding:6px 10px; }
  .msg { color:var(--acc); margin-bottom:12px; }
  .empty { color:var(--mut); text-align:center; padding:20px; }
</style>
</head>
<body>
<div class="wrap">
  <h1>Обменник файлов</h1>
  <?php if ($msg): ?><div class="msg"><?= htmlspecialchars($msg) ?></div><?php endif; ?>

  <form class="card" method="post" enctype="multipart/form-data" action="<?= $self ?>?token=<?= $t ?>">
    <input type="hidden" name="token" value="<?= htmlspecialchars((string)$token) ?>">
    <label class="drop">
      Выбрать файлы для загрузки
      <input type="file" name="f[]" multiple onchange="this.form.submit()">
    </label>
    <div style="margin-top:12px"><button type="submit">Загрузить</button></div>
  </form>

  <div class="card">
    <?php if (!$files): ?>
      <div class="empty">Пока пусто. Загрузи файл сверху — он появится здесь и на другом устройстве.</div>
    <?php else: foreach ($files as $name): $e = rawurlencode($name); ?>
      <div class="row">
        <div class="name">
          <div><?= htmlspecialchars($name) ?></div>
          <div class="meta"><?= human(filesize($DIR . '/' . $name)) ?> · <?= date('d.m H:i', filemtime($DIR . '/' . $name)) ?></div>
        </div>
        <a class="get" href="<?= $self ?>?token=<?= $t ?>&dl=<?= $e ?>">Скачать</a>
        <a class="del" href="<?= $self ?>?token=<?= $t ?>&del=<?= $e ?>" onclick="return confirm('Удалить <?= htmlspecialchars($name, ENT_QUOTES) ?>?')">✕</a>
      </div>
    <?php endforeach; endif; ?>
  </div>
</div>
</body>
</html>
