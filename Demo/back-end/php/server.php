<?php

require __DIR__ . '/src/bootstrap.php';

use DSPay\Api\Client;
use DSPay\Api\RequestBuilderException;

$merchantNo = getenv('MERCHANT_NO') ?: 'change-me-to-your-merchantNo';
$apiSecret = getenv('API_SECRET') ?: 'change-me-to-your-apiSecret';
$publicBase = rtrim(getenv('PUBLIC_BASE_URL') ?: 'http://localhost:3000', '/');
$payment = Client::payment($merchantNo, $apiSecret);

function jsonResponse($status, $body)
{
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($body, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
}

function orderData($merchantNo, $publicBase)
{
    $outOrderNo = isset($_GET['outOrderNo']) && trim($_GET['outOrderNo']) !== ''
        ? trim($_GET['outOrderNo']) : 'PHP-' . intval(microtime(true) * 1000);
    return array(
        'merchantNo' => $merchantNo,
        'outOrderNo' => $outOrderNo,
        'productPrice' => isset($_GET['productPrice']) ? $_GET['productPrice'] : '0.01',
        'productPriceCurrency' => isset($_GET['productPriceCurrency']) ? $_GET['productPriceCurrency'] : 'USD',
        'productId' => isset($_GET['productId']) ? $_GET['productId'] : 'NOVA-LIFETIME-001',
        'attach' => array('demo' => 'php', 'customerId' => 'CUST-1001'),
        'payAmount' => isset($_GET['payAmount']) ? $_GET['payAmount'] : '0.01',
        'allowedPaymentMethods' => array(),
        'returnUrl' => $publicBase . '/payment/return?outOrderNo=' . rawurlencode($outOrderNo),
        'successRedirectUrl' => $publicBase . '/payment/success?outOrderNo=' . rawurlencode($outOrderNo),
        'timestamp' => intval(microtime(true) * 1000),
    );
}

$path = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
$method = $_SERVER['REQUEST_METHOD'];

try {
    if ($method === 'GET' && $path === '/create') {
        $result = $payment->createOrder(orderData($merchantNo, $publicBase));
        if (!isset($result['checkoutUrl'])) throw new RequestBuilderException('checkoutUrl missing in DSPay response');
        header('Location: ' . $result['checkoutUrl'], true, 302);
        return;
    }
    if ($method === 'GET' && $path === '/query') {
        $query = array();
        if (isset($_GET['orderNo'])) $query['orderNo'] = $_GET['orderNo'];
        if (isset($_GET['outOrderNo'])) $query['outOrderNo'] = $_GET['outOrderNo'];
        if (count($query) === 0) { jsonResponse(400, array('code' => 'ORDER_NO_REQUIRED')); return; }
        jsonResponse(200, $payment->queryOrder($query));
        return;
    }
    if ($method === 'POST' && $path === '/notify') {
        $rawBody = file_get_contents('php://input');
        $signature = isset($_SERVER['HTTP_X_DSPAY_SIGNATURE']) ? $_SERVER['HTTP_X_DSPAY_SIGNATURE'] : '';
        if (!$payment->verifyCallback($rawBody, $signature)) {
            jsonResponse(401, array('code' => 'FAIL', 'msg' => 'signature invalid')); return;
        }
        error_log('[DSPay PHP Demo] verified callback: ' . $rawBody);
        jsonResponse(200, array('code' => 'SUCCESS', 'msg' => 'ok'));
        return;
    }
    if ($method === 'GET' && ($path === '/payment/return' || $path === '/payment/success')) {
        $outOrderNo = isset($_GET['outOrderNo']) ? $_GET['outOrderNo'] : '';
        header('Location: /query?outOrderNo=' . rawurlencode($outOrderNo), true, 302);
        return;
    }
    jsonResponse(404, array('code' => 'NOT_FOUND'));
} catch (RequestBuilderException $exception) {
    jsonResponse($exception->getCode() >= 400 ? $exception->getCode() : 500,
        array('code' => 'DEMO_ERROR', 'msg' => $exception->getMessage(), 'dspay' => $exception->getErrors()));
}
