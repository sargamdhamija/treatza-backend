@echo off
if not exist postgresql.jar (
  echo postgresql.jar not found in this folder.
  echo Download it from https://jdbc.postgresql.org/download/ and place it here first.
  pause
  exit /b 1
)
echo Compiling Treatza backend...
javac -cp .;postgresql.jar *.java
if %errorlevel% neq 0 (
  echo.
  echo Compile failed - see errors above.
  pause
  exit /b 1
)
echo Done. Run start.bat to launch the server.
pause
