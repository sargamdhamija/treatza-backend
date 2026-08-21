@echo off
if not exist .env (
  echo No .env file found - copy .env.example to .env first and fill in ADMIN_KEY + DATABASE_URL.
  pause
  exit /b 1
)
java -cp .;postgresql.jar TreatzaServer
pause
