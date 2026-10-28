<?php
require __DIR__ . '/../src/bootstrap.php';

use DSPay\Api\RequestBuilder;
use DSPay\Api\LosslessJson;

$secret = 'demo-secret';
$builder = new RequestBuilder('DSM001', $secret);
$now = 1787700000000;
$raw = '{"timestamp":1787700000000,"payAmount":1.2300,"note":" keep spaces ","attach":{"x":90071992547409931234567890,"y":1.234567890123456789}}';
$canonical = 'attach={"x":90071992547409931234567890,"y":1.234567890123456789}&note= keep spaces &payAmount=1.2300&timestamp=1787700000000';
$actual = $builder->buildCallbackPayload(get_object_vars(LosslessJson::parse($raw)));
if ($actual !== $canonical) throw new RuntimeException('Lossless callback canonical mismatch: ' . $actual);
$signature = hash_hmac('sha256', $canonical, $secret);
foreach (array($now, $now - 300000, $now + 300000) as $clock) {
    if (!$builder->verifyCallback($raw, $signature, $clock)) throw new RuntimeException('Valid callback rejected');
}
foreach (array($now - 300001, $now + 300001) as $clock) {
    if ($builder->verifyCallback($raw, $signature, $clock)) throw new RuntimeException('Expired/future callback accepted');
}
foreach (array('{"timestamp":1787700000000,"timestamp":1787700000000}', '{"timestamp":1787700000000} trailing', '{"timestamp":"1787700000000"}', '{"timestamp":1787700000000.0}') as $invalid) {
    if ($builder->verifyCallback($invalid, $signature, $now)) throw new RuntimeException('Invalid callback accepted');
}
echo "VerifyCallback: PASS\n";
