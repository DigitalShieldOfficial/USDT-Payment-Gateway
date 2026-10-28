<?php
require __DIR__ . '/../src/bootstrap.php';

use DSPay\Api\Payment;
use DSPay\Api\RequestBuilderException;

// Bind a local non-listening socket so no external service or real order is used.
$socket = stream_socket_server('tcp://127.0.0.1:0', $errno, $error, STREAM_SERVER_BIND);
if ($socket === false) throw new RuntimeException('Cannot reserve local test port: ' . $error);
$address = stream_socket_get_name($socket, false);
$payment = new Payment('DSM001', 'demo-secret', 'http://' . $address);
$previousHandlerCalls = 0;
set_error_handler(function () use (&$previousHandlerCalls) { $previousHandlerCalls++; return true; });
ob_start();
try {
    try {
        $payment->queryOrder(array('outOrderNo' => 'NETWORK-ERROR-TEST'));
        throw new RuntimeException('Expected a transport failure');
    } catch (RequestBuilderException $exception) {
        if ($exception->getCode() !== 502) throw new RuntimeException('Network errors must map to HTTP 502');
        if (strpos($exception->getMessage(), 'DSPay request failed') === false) throw new RuntimeException('Missing transport failure description');
    }
    if (ob_get_contents() !== '') throw new RuntimeException('Network warnings leaked into the response');
    if ($previousHandlerCalls !== 0) throw new RuntimeException('Transport warning was not captured');
    trigger_error('Verify previous handler restoration', E_USER_WARNING);
    if ($previousHandlerCalls !== 1) throw new RuntimeException('Previous error handler was not restored');
} finally {
    ob_end_clean();
    restore_error_handler();
    fclose($socket);
}
echo "ValidateNetworkError: PASS\n";
