<?php

// Integration test: start server.php with valid DSPay sandbox credentials first.
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
if (!preg_match('#/checkout/[0-9]+$#', $location)) {
    throw new RuntimeException('Expected checkoutUrl, got: ' . $location);
}
echo 'ValidateHttpCreate: PASS, Location=' . $location . PHP_EOL;
