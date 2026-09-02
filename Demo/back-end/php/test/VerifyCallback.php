<?php
require __DIR__ . '/../src/bootstrap.php';

use DSPay\Api\Client;
use DSPay\Api\RequestBuilder;

const MERCHANT_NO = 'change-me-to-your-merchantNo';
const API_SECRET  = 'change-me-to-your-apiSecret';

$payment = Client::payment(MERCHANT_NO, API_SECRET);
$builder = new RequestBuilder(MERCHANT_NO, API_SECRET);

// ===== 本地测试（模拟回调）=====
$rawBody   = '{"orderNo":"DS001","status":"COMPLETED","payAmount":"0.01"}';
$body = json_decode($rawBody, true);
$signature = hash_hmac('sha256', $builder->buildCallbackPayload($body), API_SECRET);

// ===== 真实回调处理（部署时启用，注释上方本地测试）=====
// $rawBody   = file_get_contents('php://input');
// $signature = $_SERVER['HTTP_X_DSPAY_SIGNATURE'] ?? '';

// 验签并响应
if ($payment->verifyCallback($rawBody, $signature)) {
    // 验签成功 —— 处理业务逻辑（如更新订单状态）
    $data = json_decode($rawBody, true);
    error_log('[DSPay] verify OK, body=' . $rawBody);

    // 必须返回大写 SUCCESS，否则 DSPay 会重试
    echo json_encode(array('code' => 'SUCCESS', 'msg' => 'ok'));
} else {
    // 验签失败
    error_log('[DSPay] verify FAILED, signature=' . $signature);
    http_response_code(401);
    echo json_encode(array('code' => 'FAIL', 'msg' => 'signature invalid'));
}
