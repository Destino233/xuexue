@echo off
set "ROOT=%~dp0.."
if "%XUEXUE_PADDLEOCR_PYTHON%"=="" (set "PYTHON=python") else (set "PYTHON=%XUEXUE_PADDLEOCR_PYTHON%")
set "PYTHONPATH=%ROOT%\lib;%PYTHONPATH%"
"%PYTHON%" "%ROOT%\paddleocr_sidecar.py" %*
