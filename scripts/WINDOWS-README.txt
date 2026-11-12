QUICK DROP FOR WINDOWS

1. Extract the entire ZIP into a folder. Do not run the EXE inside the ZIP.
2. Double-click Quick Drop.exe. Java is included; no separate install is needed.
3. Your browser opens Quick Drop. On first launch, create your owner PIN.
4. Keep the Quick Drop window open while transferring. Close it to stop the app.

Keep the app and runtime folders beside Quick Drop.exe.
If the browser does not open, visit http://localhost:8080.
If port 8080 is already in use, stop the other Quick Drop instance first.

Saved files, owner PIN, database, and logs are in:
  %LOCALAPPDATA%\Quick Drop

To update: close Quick Drop, extract the new ZIP into a new folder, and launch it.
Your saved data stays in the folder above. Deleting an old extracted app folder
will not delete that saved data. Back up the entire data folder while the app is
stopped. Do not run different versions at the same time.

This release uses HTTP on your trusted local network. Windows Firewall may ask
about network access; allow access on your trusted Private network if needed.
The executable is not code-signed, so Windows may display a publisher warning.
Download releases only from the project's GitHub Releases page.

Developer checkout data is separate. See the repository README before migrating
existing files. Logs are in %LOCALAPPDATA%\Quick Drop\logs\quickdrop.log.
