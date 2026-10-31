@echo off
cd /d "%~dp0.."
echo Starting 3DShop server on http://localhost:8080  (admin: /admin)
java -cp "server/lib/*" server/Server.java
pause
