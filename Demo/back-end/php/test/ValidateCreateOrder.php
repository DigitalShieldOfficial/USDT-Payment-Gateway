<?php

require __DIR__ . '/../src/bootstrap.php';

use DSPay\Api\Client;
use DSPay\Api\RequestBuilder;
use DSPay\Api\RequestBuilderException;

const TEST_MERCHANT_NO = 'DSM1';
const TEST_API_SECRET = 'test-api-secret';

function assertSameValue($expected, $actual, $message)
{
    if ($expected !== $actual) {
        throw new RuntimeException(
            $message . ': expected=' . var_export($expected, true)
            . ', actual=' . var_export($actual, true)
        );
    }
}

function assertRejectsOrderNo(array $data, $message)
{
    $payment = Client::payment(TEST_MERCHANT_NO, TEST_API_SECRET);

    try {
        $payment->createOrder($data);
    } catch (RequestBuilderException $exception) {
        return;
    }

    throw new RuntimeException($message);
}

function assertRejectsPayAmount($payAmount, $message)
{
    $payment = Client::payment(TEST_MERCHANT_NO, TEST_API_SECRET);

    try {
        $payment->createOrder(array(
            'outOrderNo' => 'PHP-DEMO-001',
            'payAmount' => $payAmount,
            'timestamp' => '1717689600000',
        ));
    } catch (RequestBuilderException $exception) {
        return;
    }

    throw new RuntimeException($message);
}

$payment = Client::payment(TEST_MERCHANT_NO, TEST_API_SECRET);
$url = $payment->createOrder(array(
    'outOrderNo' => '  PHP-DEMO-001  ',
    'payAmount' => '0.01',
    'timestamp' => '1717689600000',
));

$query = parse_url($url, PHP_URL_QUERY);
parse_str($query, $params);

assertSameValue(TEST_MERCHANT_NO, $params['merchantNo'], 'merchantNo mismatch');
assertSameValue('PHP-DEMO-001', $params['outOrderNo'], 'outOrderNo mismatch');
assertSameValue('0.01', $params['payAmount'], 'payAmount mismatch');
assertSameValue('1717689600000', $params['timestamp'], 'timestamp mismatch');

$canonical = 'merchantNo=DSM1&outOrderNo=PHP-DEMO-001'
    . '&payAmount=0.01&timestamp=1717689600000';
$expectedSignature = hash_hmac('sha256', $canonical, TEST_API_SECRET);
assertSameValue($expectedSignature, $params['signature'], 'signature mismatch');

$preciseUrl = $payment->createOrder(array(
    'outOrderNo' => 'PHP-DEMO-002',
    'payAmount' => '100.12',
    'timestamp' => '1717689600000',
));
parse_str(parse_url($preciseUrl, PHP_URL_QUERY), $preciseParams);
assertSameValue('100.12', $preciseParams['payAmount'], 'payAmount string must be preserved');

$baseData = array('payAmount' => '0.01', 'timestamp' => '1717689600000');
assertRejectsOrderNo($baseData, 'Missing outOrderNo must be rejected');
assertRejectsOrderNo(
    array_merge($baseData, array('outOrderNo' => '   ')),
    'Blank outOrderNo must be rejected'
);

assertRejectsPayAmount(100, 'Numeric payAmount must be rejected');
assertRejectsPayAmount('1e2', 'Scientific notation must be rejected');
assertRejectsPayAmount('100.123', 'More than 2 decimal places must be rejected');
assertRejectsPayAmount('0', 'Zero payAmount must be rejected');
assertRejectsOrderNo(
    array_merge($baseData, array('outOrderNo' => str_repeat('A', 65))),
    'Over-64-character outOrderNo must be rejected'
);

$requestBuilder = new RequestBuilder(TEST_MERCHANT_NO, TEST_API_SECRET);
try {
    $requestBuilder->signOrder('', '0.01', '1717689600000');
    throw new RuntimeException('RequestBuilder must reject blank outOrderNo');
} catch (RequestBuilderException $exception) {
    // Expected: direct signing cannot bypass required outOrderNo validation.
}

echo "ValidateCreateOrder: PASS" . PHP_EOL;
