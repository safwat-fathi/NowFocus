; NowFocus NSIS installer hooks. Merged in only for Windows builds via
; tauri.windows.conf.json (installMode is perMachine there, so the installer
; runs elevated and $INSTDIR is Program Files — a prerequisite for `sc create`
; to register a SYSTEM service; the default currentUser mode would fail here
; silently).
;
; The privileged background service is shipped as a Tauri externalBin sidecar,
; installed next to the app with the target-triple stripped, i.e.
; $INSTDIR\now-focus-service.exe.
;
; *** UNVERIFIED — no Windows machine in this build environment. Confirm on a
; real install that (1) the sidecar lands at exactly this path and (2) the
; service registers and starts. If the path is wrong it's a one-line fix here.

!macro NSIS_HOOK_PREINSTALL
  ; Stop a running service first so an upgrade can overwrite its locked exe.
  ; Harmless when the service isn't installed yet.
  nsExec::Exec 'sc stop NowFocusService'
!macroend

!macro NSIS_HOOK_POSTINSTALL
  ; Create if absent (fails harmlessly on upgrade when it already exists),
  ; then point it at the freshly-installed exe and start it. `sc` needs the
  ; space after each `key=`.
  nsExec::Exec 'sc create NowFocusService binPath= "$INSTDIR\now-focus-service.exe" start= auto DisplayName= "NowFocus Service"'
  nsExec::Exec 'sc config NowFocusService binPath= "$INSTDIR\now-focus-service.exe" start= auto'
  nsExec::Exec 'sc start NowFocusService'
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  ; Strip our hosts-file regions + commitment state first (the exe still
  ; exists here), so uninstalling doesn't orphan a permanent, never-expiring
  ; block. Then remove the service before its files are deleted.
  nsExec::Exec '"$INSTDIR\now-focus-service.exe" --cleanup'
  nsExec::Exec 'sc stop NowFocusService'
  nsExec::Exec 'sc delete NowFocusService'
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
!macroend
