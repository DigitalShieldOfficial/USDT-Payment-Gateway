<?php
require __DIR__ . '/../src/bootstrap.php';

use DSPay\Api\Payment;
use DSPay\Api\RequestBuilderException;

// Inspect configuration only; this test never calls the DSPay API.
$previous = getenv('DSPAY_BASE_URL');
$field = new ReflectionProperty('DSPay\\Api\\Payment', 'baseUrl');
if (PHP_VERSION_ID < 80100) $field->setAccessible(true);

function assertApiBase($expected, $payment, $field)
{
    if ($field->getValue($payment) !== $expected) {
        throw new RuntimeException('API base URL did not preserve the expected value');
    }
}

try {
    putenv('DSPAY_BASE_URL');
    assertApiBase('https://pay-api.ds.pro', new Payment('DSM001', 'demo-secret'), $field);
    putenv('DSPAY_BASE_URL=http://localhost:12345');
    assertApiBase('http://localhost:12345', new Payment('DSM001', 'demo-secret'), $field);
    assertApiBase('http://localhost:23456', new Payment('DSM001', 'demo-secret', 'http://localhost:23456'), $field);
    foreach (array('', ' https://pay-api.ds.pro', 'https://pay-api.ds.pro/') as $invalid) {
        putenv('DSPAY_BASE_URL=' . $invalid);
        try {
            new Payment('DSM001', 'demo-secret');
            throw new RuntimeException('Invalid API origin must be rejected');
        } catch (RequestBuilderException $expected) {
            if (strpos($expected->getMessage(), 'DSPAY_BASE_URL') === false) {
                throw new RuntimeException('Missing field name');
            }
        }
    }
} finally {
    if ($previous === false) putenv('DSPAY_BASE_URL');
    else putenv('DSPAY_BASE_URL=' . $previous);
}
echo "ValidateApiBase: PASS\n";
