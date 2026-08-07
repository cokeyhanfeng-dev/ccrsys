# 前端镜像:Nginx 托管
# 注意:本环境无法访问 docker.io 拉取 node 镜像,改为宿主机先执行 `npm run build`,再构建本镜像。
# 有网环境可恢复多阶段构建(FROM node:20-alpine AS build ... COPY --from=build /app/dist ...)。
FROM nginx:alpine
COPY dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
