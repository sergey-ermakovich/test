<?php
// Tiny drop box for Zeekr Probe reports. Put it anywhere on PHP hosting, e.g.
// https://example.com/zeekr/upload.php, create a writable "reports/" folder
// next to it, and set TOKEN below. Then:
//
//   POST upload.php?token=T      body = report text  → saves reports/<timestamp>.txt
//   GET  upload.php?token=T&list=1                   → lists saved reports
//   GET  upload.php?token=T&file=<name>              → returns one report as text/plain
//
// Nothing is served without the token.

const TOKEN = 'change-me';
const DIR = __DIR__ . '/reports';

header('Content-Type: text/plain; charset=utf-8');

if (!isset($_GET['token']) || !hash_equals(TOKEN, (string)$_GET['token'])) {
    http_response_code(403);
    exit("forbidden\n");
}
if (!is_dir(DIR) && !mkdir(DIR, 0755, true)) {
    http_response_code(500);
    exit("cannot create reports dir\n");
}

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $body = file_get_contents('php://input');
    if ($body === false || strlen($body) === 0) {
        http_response_code(400);
        exit("empty body\n");
    }
    $suffix = isset($_GET['name']) ? preg_replace('/[^A-Za-z0-9_.-]/', '', (string)$_GET['name']) : '';
    $name = date('Ymd-His') . ($suffix !== '' ? "-$suffix" : '') . '.txt';
    if (file_put_contents(DIR . '/' . $name, $body) === false) {
        http_response_code(500);
        exit("write failed\n");
    }
    echo "saved $name (" . strlen($body) . " bytes)\n";
    exit;
}

if (isset($_GET['file'])) {
    $name = basename((string)$_GET['file']);
    $path = DIR . '/' . $name;
    if (!preg_match('/^[A-Za-z0-9_.-]+\.txt$/', $name) || !is_file($path)) {
        http_response_code(404);
        exit("no such report\n");
    }
    readfile($path);
    exit;
}

$files = glob(DIR . '/*.txt') ?: [];
rsort($files);
foreach ($files as $f) {
    echo basename($f) . "\t" . filesize($f) . "\n";
}
