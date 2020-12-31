@IF NOT "%JAVA_HOME%" == "" GOTO OK
@ECHO Please set JAVA_HOME environment variable, for example "SET JAVA_HOME=c:\java\jdk180"
@GOTO EXIT
@:OK
SET HOME=.
SET BIN=%HOME%\bin
SET SRC=%HOME%\src
SET SRCLIST=~srclist.txt
SET JARNAME=ZInterpreter.jar
SET MANIFESTNAME=manifest.txt
PUSHD .
IF EXIST %BIN% RMDIR /S %BIN%
MKDIR %BIN%
IF EXIST %SRCLIST% DEL %SRCLIST%
DIR /S /B /A-D %SRC% | FINDSTR /V /C:internal > %SRCLIST%
%JAVA_HOME%\bin\javac -source 1.8 -target 1.8 -d %BIN% @%SRCLIST%
DEL %SRCLIST%
CD %BIN%
IF EXIST %JARNAME% DEL %JARNAME%
%JAVA_HOME%\bin\jar cfm %JARNAME% ..\%MANIFESTNAME% *
POPD
MOVE %BIN%\%JARNAME% .
@:EXIT