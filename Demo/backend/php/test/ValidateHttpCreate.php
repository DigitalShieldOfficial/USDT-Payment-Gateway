<?php

// Optional live integration test: start server.php with valid merchant credentials.
// The default DSPay API is mainnet; order creation calls the real API.
$createUrl = getenv('TEST_CREATE_URL');
if (!$createUrl) {
    echo "ValidateHttpCreate: SKIP (set TEST_CREATE_URL for a live integration test)\n";
    exit(0);
}

$context = stream_context_create(array('http' => array(
    'method' => 'GET', 'ignore_errors' => true, 'follow_location' => 0, 'timeout' => 15,
)));
file_get_contents($createUrl, false, $context);
if (function_exists('http_get_last_response_headers')) {
    $responseHeaders = http_get_last_response_headers();
} else {
    $legacyHeaderVariable = 'http_response_header';
    $responseHeaders = isset($$legacyHeaderVariable) ? $$legacyHeaderVariable : array();
}
if (!isset($responseHeaders[0]) || strpos($responseHeaders[0], '302') === false) {
    throw new RuntimeException('Expected HTTP 302 from /create');
}
$location = '';
foreach ($responseHeaders as $header) {
    if (stripos($header, 'Location: ') === 0) $location = trim(substr($header, 10));
}
$parts = parse_url($location);
$query = array();
if (isset($parts['query'])) parse_str($parts['query'], $query);
if (!$parts || !isset($parts['scheme'], $parts['host'], $parts['path'])
    || !in_array($parts['scheme'], array('http', 'https'), true)
    || !preg_match('#/checkout/(?:DS)?[0-9]+$#', $parts['path'])
    || !isset($query['x-digitalshield-temp-token'])
    || !is_string($query['x-digitalshield-temp-token'])
    || $query['x-digitalshield-temp-token'] === '') {
    throw new RuntimeException('Expected checkoutUrl, got: ' . $location);
}
echo 'ValidateHttpCreate: PASS (checkout path and temporary token present)' . PHP_EOL;
