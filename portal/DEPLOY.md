# Phoenix Portal Deployment

Simple deployment using Railway (backend + database) and Vercel (frontend).

## Backend (Railway)

1. Create a new Railway project
2. Add a **PostgreSQL** database service
3. Add a new service from GitHub, point to `portal/apps/backend`
4. Set these environment variables:
   ```
   DATABASE_URL=<from Railway PostgreSQL>
   DATABASE_USER=<from Railway PostgreSQL>
   DATABASE_PASSWORD=<from Railway PostgreSQL>
   JWT_SECRET=<generate a random 32+ character string>
   ```
5. Railway will auto-detect the Dockerfile and deploy

## Frontend (Vercel)

1. Import your repo to Vercel
2. Set the root directory to `portal/apps/web`
3. Set environment variable:
   ```
   NEXT_PUBLIC_API_URL=https://your-railway-app.railway.app
   ```
4. Deploy

## Local Development

```bash
# Start PostgreSQL (use Docker or local install)
docker run -d --name phoenix-db -p 5432:5432 \
  -e POSTGRES_DB=phoenix_portal \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  postgres:15

# Backend
cd portal/apps/backend
./gradlew run

# Frontend (in another terminal)
cd portal/apps/web
npm run dev
```

## Environment Variables

### Backend (Ktor)
| Variable | Description | Default |
|----------|-------------|---------|
| PORT | Server port | 8080 |
| DATABASE_URL | PostgreSQL JDBC URL | localhost:5432/phoenix_portal |
| DATABASE_USER | Database username | postgres |
| DATABASE_PASSWORD | Database password | postgres |
| JWT_SECRET | JWT signing secret | dev-secret (change in prod!) |

### Frontend (Next.js)
| Variable | Description | Default |
|----------|-------------|---------|
| NEXT_PUBLIC_API_URL | Backend API URL | http://localhost:8080 |
