# Demo 安全配置与部署

## 快速启动保持简单

准备好对应语言运行环境后，执行 `./start.sh`，按提示输入商户号和 apiSecret；或提前设置 `MERCHANT_NO`、`API_SECRET`。无需修改代码。四种语言均无默认商户凭证，缺少配置时拒绝运行。配置按原值处理，不修剪或自动纠正。Java 直接启动仍支持 `-DmerchantNo`、`-DapiSecret`，推荐通过环境变量传递密钥，避免命令行泄露。

PHP 请使用 `./start.sh`：脚本在监听端口前预检凭证和 URL。直接使用 `php -S ... server.php` 时，PHP 仅在请求到达后执行路由，非法配置会使路由报错，但不会终止 PHP 开发服务器进程。PHP 内置服务器仅供开发联调。

## 可选配置

| 配置 | 默认行为 | 适用场景 |
| --- | --- | --- |
| `PUBLIC_BASE_URL` | 不发送 `returnUrl`、`successRedirectUrl`，付款后停留收银台 | 需要支付后返回商户站点时，填写可信的 HTTP(S) 源地址 |
| `PORT` | `3000` | 端口冲突或部署需要 |
| `DSPAY_BASE_URL` | `https://pay-api.ds.pro` | 切换联调环境 |
| `FRONT_END_DIR` | Demo 自带前端目录 | 托管其他演示页面 |

例如在对应后端目录执行：

```bash
PUBLIC_BASE_URL=https://shop.example.com ./start.sh
```

`PUBLIC_BASE_URL` 必须是完整 HTTP(S) 源地址，不允许尾部斜杠、路径、查询参数、片段、用户名密码或空白。未提供与提供空字符串不同：后者报错。返回链接只由此配置生成，不信任请求的 `Host`、`X-Forwarded-Host` 或 `X-Forwarded-Proto`；修改 ngrok 域名后需更新配置并重启。

该配置不控制服务监听地址，也不代替商户后台登记的回调地址。真实支付回调仍需将 `https://shop.example.com/notify` 单独登记到商户后台。

## 公网部署边界

Demo 的创建、查询接口不内置用户登录。公开创建订单可以是正常业务，但生产系统需自行控制金额、商品、订单归属、敏感查询结果与调用频率；不要直接把 Demo 当作完整商户系统。Node JSON 响应不再开放通配符 CORS，请从后端提供的同源页面访问。跨域接入应由商户的代理或应用明确设置允许来源。

服务监听所有网卡以便容器和公网回调联调；是否公网可达由网络部署决定。同机后端调用场景可通过 Nginx 限制创建、查询接口，仅让回调公开访问。以下为合并到现有 HTTPS `server` 块的示意片段：

```nginx
client_max_body_size 64k;

location = /notify {
    proxy_pass http://127.0.0.1:3000;
}

location = /create {
    allow 127.0.0.1;
    allow ::1;
    deny all;
    proxy_pass http://127.0.0.1:3000;
}

location = /query {
    allow 127.0.0.1;
    allow ::1;
    deny all;
    proxy_pass http://127.0.0.1:3000;
}

# 不向公网转发其他 Demo 路径；如需商店页，应另行配置业务访问控制。
location / {
    return 404;
}
```

此示例要求创建、查询由同机后端调用，公网浏览器直接调用将被拒绝。不同服务器、容器或公开收款页面需按真实网络与业务调整访问规则。必须通过防火墙或容器端口映射限制后端 3000 端口，只允许可信调用方或代理访问，避免绕过 Nginx。代理规则只是示例，不自动安装或生效。

## 回调处理

四种实现将回调请求体限制为 65536 字节，超限返回 HTTP 413。Node、Java 对流式读取同样执行上限；Python 要求合法的 `Content-Length`，不支持分块请求。PHP 路由限制读取大小，但 PHP 开发服务器可能提前缓冲请求，因此公网入口仍需设置请求体上限。Nginx 还可按环境配置读取超时、限流等部署保护。

通过验签只能确认消息签名及时间窗有效。示例返回 `SUCCESS` 仅表示演示验签通过；生产必须核对订单存在、商户、金额、币种与状态，并在幂等提交业务结果后应答成功。浏览器支付跳转也不能作为到账依据。
