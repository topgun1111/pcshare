@echo off
rem mpv acilinca takip programi kendiliginden baslar (track.lua). Videoyu bu dosyanin ustune suruklemek de olur.
start "" "%~dp0mpv\mpv.exe" --player-operation-mode=pseudo-gui %*
