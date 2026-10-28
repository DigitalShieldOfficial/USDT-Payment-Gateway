<?php

require __DIR__ . '/src/bootstrap.php';

use DSPay\Api\Client;
use DSPay\Api\Payment;
use DSPay\Api\RequestBuilderException;

$merchantNo = getenv('MERCHANT_NO') ?: 'change-me-to-your-merchantNo';
$apiSecret = getenv('API_SECRET') ?: 'change-me-to-your-apiSecret';
$payment = Client::payment($merchantNo, $apiSecret);

function jsonResponse($status, $body)
{
    global $apiSecret;
    Payment::logEvent("DEMO_RESPONSE", array("path" => $_SERVER["REQUEST_URI"], "status" => $status, "body" => $body), $apiSecret);
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    echo \DSPay\Api\LosslessJson::encode($body);
}

function requestOrigin()
{
    $local = in_array($_SERVER['REMOTE_ADDR'], array('127.0.0.1', '::1', '::ffff:127.0.0.1'), true);
    $host = $local && isset($_SERVER['HTTP_X_FORWARDED_HOST']) ? $_SERVER['HTTP_X_FORWARDED_HOST'] : $_SERVER['HTTP_HOST'];
    $proto = $local && isset($_SERVER['HTTP_X_FORWARDED_PROTO']) ? $_SERVER['HTTP_X_FORWARDED_PROTO'] : 'http';
    if ($proto !== 'http' && $proto !== 'https') throw new RequestBuilderException('X-Forwarded-Proto must be http or https', 400);
    return Payment::origin($proto . '://' . $host, 'Request origin');
}

function parameters($allowed)
{
    $query = isset($_SERVER['QUERY_STRING']) ? $_SERVER['QUERY_STRING'] : '';
    $fields = array();
    if ($query === '') return $fields;
    $pairs = array_filter(explode('&', $query), 'strlen');
    if (count($pairs) > 20) throw new \InvalidArgumentException('At most 20 query fields are allowed');
    foreach ($pairs as $pair) {
        $parts = explode('=', $pair, 2);
        foreach ($parts as $part) if (preg_match('/%(?![0-9a-fA-F]{2})/', $part)) throw new \InvalidArgumentException('Invalid query encoding');
        $key = urldecode($parts[0]); $value = isset($parts[1]) ? urldecode($parts[1]) : '';
        if (!preg_match('//u', $key . $value)) throw new \InvalidArgumentException('Query fields must be valid UTF-8');
        if (!in_array($key, $allowed, true)) throw new \InvalidArgumentException('Unsupported query field: ' . $key);
        if (array_key_exists($key, $fields)) throw new \InvalidArgumentException($key . ' must occur exactly once');
        $fields[$key] = $value;
    }
    return $fields;
}
function identifier($value, $field)
{
    $pattern = $field === 'outOrderNo' ? '/^[A-Za-z0-9-]{1,64}$/D' : '/^(?:DS)?[1-9][0-9]{14,18}$/D';
    if (!preg_match($pattern, $value)) throw new \InvalidArgumentException($field . ' has an invalid format; whitespace is not allowed');
    return $value;
}
function amount($value, $field, $integers, $decimals, $minimum = false)
{
    if (!preg_match('/^(?:0|[1-9][0-9]{0,' . ($integers - 1) . '})(?:\.[0-9]{1,' . $decimals . '})?$/D', $value) || ($minimum && (float)$value < 0.01)) {
        throw new \InvalidArgumentException($field . ' must be a plain nonnegative decimal with at most ' . $integers . ' integer and ' . $decimals . ' fractional digits, no leading zeros or whitespace' . ($minimum ? '; minimum 0.01' : ''));
    }
    return $value;
}
function orderData($merchantNo, $publicBase)
{
    $args = parameters(array('outOrderNo', 'payAmount', 'productPrice', 'productPriceCurrency', 'productId'));
    if (isset($args['productPriceCurrency']) && $args['productPriceCurrency'] !== 'USD') throw new \InvalidArgumentException('productPriceCurrency must be USD for this storefront');
    if (!array_key_exists('payAmount', $args)) throw new \InvalidArgumentException('payAmount is required; minimum 0.01');
    $number = array_key_exists('outOrderNo', $args) ? identifier($args['outOrderNo'], 'outOrderNo') : 'DEMO-' . bin2hex(openssl_random_pseudo_bytes(16));
    $order = array('merchantNo' => $merchantNo, 'outOrderNo' => $number, 'payAmount' => amount($args['payAmount'], 'payAmount', 12, 2, true),
        'attach' => array('demo' => 'php', 'customerId' => 'CUST-1001'), 'allowedPaymentMethods' => array(),
        'returnUrl' => $publicBase . '/payment/return?outOrderNo=' . rawurlencode($number),
        'successRedirectUrl' => $publicBase . '/payment/success?outOrderNo=' . rawurlencode($number), 'timestamp' => (int)floor(microtime(true) * 1000));
    if (array_key_exists('productPrice', $args)) { $order['productPrice'] = amount($args['productPrice'], 'productPrice', 14, 6); $order['productPriceCurrency'] = 'USD'; }
    if (array_key_exists('productPriceCurrency', $args)) $order['productPriceCurrency'] = $args['productPriceCurrency'];
    if (array_key_exists('productId', $args)) {
        preg_match_all('/./us', $args['productId'], $chars);
        if (count($chars[0]) > 64) throw new \InvalidArgumentException('productId must be at most 64 characters');
        $order['productId'] = $args['productId'];
    }
    return $order;
}

$path = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
$method = $_SERVER['REQUEST_METHOD'];

if (in_array($path, array("/create", "/query", "/notify"), true)) {
    Payment::logEvent("DEMO_REQUEST", array("method" => $method, "target" => $_SERVER["REQUEST_URI"], "parameters" => $_GET), $apiSecret);
}

try {
    if ($method === 'GET' && $path === '/create') {
        $result = $payment->createOrder(orderData($merchantNo, requestOrigin()));
        $checkout = is_object($result) && isset($result->checkoutUrl) ? $result->checkoutUrl : null;
        if (!is_string($checkout) || preg_match('/[\s\x00-\x1f]/', $checkout) || !preg_match('~^https?://~D', $checkout) || !parse_url($checkout, PHP_URL_HOST)) throw new RequestBuilderException('DSPay create response must contain a complete checkoutUrl', 502);
        Payment::logEvent("DEMO_RESPONSE", array("path" => $path, "status" => 302, "location" => $checkout, "body" => ""), $apiSecret);
        header('Location: ' . $checkout, true, 302);
        return;
    }
    if ($method === 'GET' && $path === '/query') {
        $query = parameters(array('orderNo', 'outOrderNo'));
        if (empty($query['orderNo']) && empty($query['outOrderNo'])) throw new \InvalidArgumentException('orderNo or outOrderNo is required');
        foreach ($query as $key => $value) if ($value !== '') identifier($value, $key);
        jsonResponse(200, $payment->queryOrder($query));
        return;
    }
    if ($method === 'POST' && $path === '/notify') {
        $rawBody = file_get_contents('php://input');
        if (!preg_match('//u', $rawBody)) { jsonResponse(400, array("code" => "FAIL", "msg" => "Callback must be UTF-8 JSON")); return; }
        $signature = isset($_SERVER['HTTP_X_DSPAY_SIGNATURE']) ? $_SERVER['HTTP_X_DSPAY_SIGNATURE'] : '';
        Payment::logEvent("CALLBACK_REQUEST", array("body" => $rawBody, "signature" => $signature === "" ? "missing" : "[REDACTED]"), $apiSecret);
        if (!$payment->verifyCallback($rawBody, $signature)) {
            jsonResponse(401, array('code' => 'FAIL', 'msg' => 'Invalid signature, JSON, or timestamp (requires ±5 minutes)')); return;
        }
        Payment::logEvent("CALLBACK_VERIFIED", array("result" => "signature and timestamp valid"), $apiSecret);
        jsonResponse(200, array('code' => 'SUCCESS', 'msg' => 'ok'));
        return;
    }
    // returnUrl (cancel/return) -> store front page; successRedirectUrl -> order query
    if ($method === 'GET' && $path === '/payment/return') {
        header('Location: /', true, 302);
        return;
    }
    if ($method === 'GET' && $path === '/payment/success') {
        $args = parameters(array('outOrderNo'));
        if (!array_key_exists('outOrderNo', $args)) throw new \InvalidArgumentException('outOrderNo is required');
        $outOrderNo = identifier($args['outOrderNo'], 'outOrderNo');
        header('Location: /query?outOrderNo=' . rawurlencode($outOrderNo), true, 302);
        return;
    }
    // GET / serves the front-end page: same origin as the API, so opening the request URL gives the full demo.
    // FRONT_END_DIR env var overrides the default repo layout (Demo/front-end, relative to this script's ../..).
    if ($method === 'GET') {
        $frontEndDir = getenv('FRONT_END_DIR') ?: __DIR__ . '/../../front-end';
        $rel = ($path === '/' || $path === '/index.html') ? 'index.html' : rawurldecode(substr($path, 1));
        $root = realpath($frontEndDir);
        $file = realpath($frontEndDir . '/' . $rel);
        // Path-traversal guard: resolved file must stay inside the front-end directory.
        if ($root !== false && $file !== false && strpos($file, $root) === 0 && is_file($file)) {
            header('Content-Type: ' . (substr($file, -5) === '.html' ? 'text/html; charset=utf-8' : 'application/octet-stream'));
            readfile($file);
            return;
        }
    }
    jsonResponse(404, array('code' => 'NOT_FOUND'));
} catch (\InvalidArgumentException $exception) {
    jsonResponse(400, array('code' => 'PARAM_ERROR', 'message' => $exception->getMessage()));
} catch (RequestBuilderException $exception) {
    jsonResponse(502, array('code' => 'DSPAY_ERROR', 'message' => $exception->getMessage()));
}
