// 배포 모델을 코드 변경 없이 둘 다 커버하는 설정.
//
// 1) dev / 분리배포(decoupled): rewrites로 /api/* 를 Spring(BACKEND_ORIGIN)으로 프록시.
//    프론트가 같은 오리진(/api)을 호출하므로 브라우저 CORS가 없다.
// 2) jar 통합배포(BUILD_TARGET=static): output:'export'로 정적 산출(out/).
//    Spring이 프론트와 /api 를 같은 오리진으로 서빙하므로 프록시 불필요.
//    (주의: output:'export'에서는 rewrites가 동작하지 않는다 — 같은 오리진이라 필요도 없음)
//
// 즉 프론트 코드는 항상 상대경로 /api 를 부르고, 배포 방식만 빌드 플래그로 가른다.

const backendOrigin = process.env.BACKEND_ORIGIN || 'http://localhost:8080';
const staticExport = process.env.BUILD_TARGET === 'static';

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  ...(staticExport ? { output: 'export' } : {}),
  async rewrites() {
    if (staticExport) return [];
    return [{ source: '/api/:path*', destination: `${backendOrigin}/api/:path*` }];
  },
};

export default nextConfig;
