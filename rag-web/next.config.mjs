/** @type {import('next').NextConfig} */
const nextConfig = {
  async rewrites() {
    // 开发/本地部署：/api/* 代理到本机 rag-server（契约 server: http://localhost:8080）
    return [
      {
        source: "/api/:path*",
        destination: "http://localhost:8080/api/:path*",
      },
    ];
  },
};

export default nextConfig;
