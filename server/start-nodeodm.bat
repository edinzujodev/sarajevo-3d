@echo off
REM Starts OpenDroneMap's NodeODM (the 3D builder) in Docker on port 3000.
REM Needs Docker Desktop running. First start downloads about 3 GB.
docker run -d --name nodeodm --restart unless-stopped -p 3000:3000 opendronemap/nodeodm
if errorlevel 1 docker start nodeodm
echo NodeODM is starting on http://localhost:3000
pause
