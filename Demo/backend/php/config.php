<?php
// Shared by the HTTP router and start.sh preflight. Never rewrite supplied values.
require_once __DIR__ . '/src/bootstrap.php';
function requiredConfig($name) {
    $value = getenv($name);
    if ($value === false || $value === '') throw new \InvalidArgumentException($name . ' is required');
    return $value;
}
$merchantNo = requiredConfig('MERCHANT_NO');
$apiSecret = requiredConfig('API_SECRET');
$publicBase = getenv('PUBLIC_BASE_URL');
$publicBase = $publicBase === false ? null : \DSPay\Api\Payment::origin($publicBase, 'PUBLIC_BASE_URL');
// Validate the upstream URL before the development server starts.
\DSPay\Api\Payment::origin(getenv('DSPAY_BASE_URL') === false ? 'https://pay-api.ds.pro' : getenv('DSPAY_BASE_URL'), 'DSPAY_BASE_URL');
