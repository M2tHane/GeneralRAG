<!--
来源：https://nginx.org/en/docs/http/ngx_http_proxy_module.html
抓取时间：2026-09-14
说明：本文件为评测语料，内容整理自 Nginx 官方文档，仅用于本地知识库评测测试。
-->

# Nginx 反向代理缓冲

## 1. proxy_buffering 的行为

语法：`proxy_buffering on | off;`，默认 **on**，可配置在 http、server、location 上下文。

- **开启缓冲（默认）**：nginx 尽快从被代理服务器读取响应，存入由 `proxy_buffer_size` 和 `proxy_buffers` 设置的缓冲区。响应放不进内存时，部分数据可写入磁盘临时文件（由 `proxy_max_temp_file_size` 与 `proxy_temp_file_write_size` 控制）。
- **关闭缓冲**：响应以同步方式传递——nginx 从后端收到多少就立即转发多少给客户端，不会尝试读取整个响应；一次能接收的最大数据量由 `proxy_buffer_size` 决定。

对 SSE（Server-Sent Events）、流式响应或长连接场景，应关闭缓冲：否则 nginx 会攒够缓冲区（或等响应结束）才向客户端转发，表现为「前端收不到增量数据、一直转圈直到完成」。

## 2. 相关指令

- `proxy_buffer_size size;` 默认 `4k|8k`（一个内存页）：读取被代理响应**第一部分**的缓冲区，通常包含响应头。响应头超过该大小会被判定为无效响应（invalid_header）。
- `proxy_buffers number size;` 默认 `8 4k|8k`：读取后端响应的缓冲区的**数量和大小**（单个连接）。
- `proxy_busy_buffers_size`：处于忙碌状态（正在向客户端发送）的缓冲区总大小上限，默认为 proxy_buffers 中两块的大小。

## 3. X-Accel-Buffering 响应头

后端应用可以通过在响应中返回 `X-Accel-Buffering: yes|no` 头，**动态地**开启或关闭该响应的代理缓冲，无需修改 nginx 配置。该头属于 `X-Accel-...` 系列，默认不会透传给客户端；如需忽略该头的处理，可在 nginx 侧使用 `proxy_ignore_headers X-Accel-Buffering;`。

流式接口的推荐组合：后端返回 `X-Accel-Buffering: no`（对个别接口动态生效），或在对应 location 显式 `proxy_buffering off;`。

## 4. 示例

```nginx
location / {
    proxy_pass http://backend;
    proxy_buffering on;
    proxy_buffer_size 8k;
    proxy_buffers 16 8k;
}

# 流式接口（SSE）单独关闭缓冲
location /api/stream {
    proxy_pass http://backend;
    proxy_buffering off;
}
```
