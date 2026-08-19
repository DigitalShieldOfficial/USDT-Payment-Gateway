<?php

$createUrl = getenv('TEST_CREATE_URL')
    ?: 'http://127.0.0.1:3000/create?payAmount=0.01&productId=DEMO-001';
$merchantNo = getenv('MERCHANT_NO') ?: 'change-me-to-your-merchantNo';
$apiSecret = getenv('API_SECRET') ?: 'change-me-to-your-apiSecret';

$context = stream_context_create(array(
    'http' => array(
        'method' => 'GET',
        'ignore_errors' => true,
        'follow_location' => 0,
    ),
));

$body = file_get_contents($createUrl, false, $context);
if ($body === false || !isset($http_response_header[0])
    || strpos($http_response_header[0], '302') === false) {
    throw new RuntimeException('Expected HTTP 302 from /create');
}

$location = null;
foreach ($http_response_header as $header) {
    if (stripos($header, 'Location: ') === 0) {
        $location = trim(substr($header, strlen('Location: ')));
        break;
    }
}

if ($location === null) {
    throw new RuntimeException('Missing Location header');
}

parse_str(parse_url($location, PHP_URL_QUERY), $params);
foreach (array('merchantNo', 'outOrderNo', 'payAmount', 'timestamp', 'signature') as $field) {
    if (!isset($params[$field]) || trim($params[$field]) === '') {
        throw new RuntimeException('Missing or blank cashier URL field: ' . $field);
    }
}

if (strpos($params['outOrderNo'], 'PHP-') !== 0) {
    throw new RuntimeException('Unexpected generated outOrderNo: ' . $params['outOrderNo']);
}

$canonical = 'merchantNo=' . $params['merchantNo']
    . '&outOrderNo=' . $params['outOrderNo']
    . '&payAmount=' . $params['payAmount']
    . '&timestamp=' . $params['timestamp'];
$expectedSignature = hash_hmac('sha256', $canonical, $apiSecret);

if (!hash_equals($expectedSignature, $params['signature'])) {
    throw new RuntimeException('Cashier URL signature mismatch');
}

$invalidUrl = preg_replace('/payAmount=[^&]*/', 'payAmount=1e2', $createUrl);
$invalidBody = file_get_contents($invalidUrl, false, $context);
if ($invalidBody === false || !isset($http_response_header[0])
    || strpos($http_response_header[0], '400') === false) {
    throw new RuntimeException('Scientific-notation payAmount must return HTTP 400');
}

$overPrecisionUrl = preg_replace('/payAmount=[^&]*/', 'payAmount=100.123', $createUrl);
$overPrecisionBody = file_get_contents($overPrecisionUrl, false, $context);
if ($overPrecisionBody === false || !isset($http_response_header[0])
    || strpos($http_response_header[0], '400') === false) {
    throw new RuntimeException('payAmount with more than 2 decimal places must return HTTP 400');
}

echo 'ValidateHttpCreate: PASS, Location=' . $location . PHP_EOL;
