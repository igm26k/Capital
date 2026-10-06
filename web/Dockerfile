FROM node:24.21.0-bookworm-slim AS dev
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
CMD ["npm", "run", "dev"]
FROM dev AS build
RUN npm run build
FROM nginx:1.28.0-alpine AS production
COPY --from=build /app/dist /usr/share/nginx/html
