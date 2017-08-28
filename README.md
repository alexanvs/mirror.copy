# mirror.copy

### Summary

This program replays move, rename, add, delete operations on files of the first directory in the second directory.
Its main advantage is doing the move operation instead of add/delete operation like all command line utilities do. It is a big advantage if you rename or move very large files.

### Usage

You need to run this program 2 times. First, before the rename and specify a source folder.
Second, after the rename and specify a target folder.
```groovy
java -jar mirror.copy.ver1.jar step1 -source <path_to_source_folder>
```
Do your filesystem operations on source folder here.
```groovy
java -jar mirror.copy.ver1.jar step2 -target <path_to_target_folder>
```

### License

Licensed under the EPL 1.0 http://www.eclipse.org/legal/epl-v10.html
