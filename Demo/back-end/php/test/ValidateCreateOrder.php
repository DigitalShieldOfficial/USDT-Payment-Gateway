<?php
require __DIR__ . '/../src/bootstrap.php';

use DSPay\Api\RequestBuilder;

function assertSameValue($expected, $actual, $message)
{
    if ($expected !== $actual) {
        throw new RuntimeException($message . ': expected=' . var_export($expected, true)
            . ', actual=' . var_export($actual, true));
    }
}

$builder = new RequestBuilder('DSM001', 'demo-secret');
$minimal = array(
    'merchantNo' => 'DSM001',
    'outOrderNo' => 'M001',
    'payAmount' => '1.00',
    'timestamp' => '1787700000000',
);
$canonical = 'merchantNo=DSM001&outOrderNo=M001&payAmount=1.00&timestamp=1787700000000';
assertSameValue($canonical, $builder->buildCreatePayload($minimal), 'create canonical');
assertSameValue(hash_hmac('sha256', $canonical, 'demo-secret'), $builder->signCreate($minimal), 'create signature');

assertSameValue('{"a":{"x":null,"y":true},"z":1}',
    $builder->canonicalJson(array('z' => 1, 'a' => array('y' => true, 'x' => null))), 'attach canonical');
assertSameValue('evm--1|0xabc,tron|TXYZ', $builder->canonicalMethods(array(
    array('networkId' => 'evm--1', 'contractAddress' => '0xABC'),
    array('networkId' => 'tron', 'contractAddress' => 'TXYZ'),
    array('networkId' => 'evm--1', 'contractAddress' => '0xabc'),
)), 'payment method canonical');

$query = array('merchantNo' => 'DSM001', 'orderNo' => '1949695024925671424', 'timestamp' => '1787700000000');
assertSameValue('merchantNo=DSM001&orderNo=1949695024925671424&timestamp=1787700000000',
    $builder->buildQueryPayload($query), 'query canonical');
assertSameValue('merchantNo=DSM001&orderNo=&outOrderNo=M001&timestamp=1787700000000',
    $builder->buildQueryPayload(array('merchantNo' => 'DSM001', 'orderNo' => '',
        'outOrderNo' => 'M001', 'timestamp' => '1787700000000')), 'query empty-string canonical');

$callback = array('status' => 'COMPLETED', 'txHash' => null, 'notifyNo' => 'N001',
    'attach' => array('z' => 1, 'a' => true));
assertSameValue('attach={"a":true,"z":1}&notifyNo=N001&status=COMPLETED',
    $builder->buildCallbackPayload($callback), 'callback canonical');

echo "ValidateCreateOrder: PASS\n";
