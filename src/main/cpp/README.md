# Native column solver

This directory contains the portable C++17 shared-library boundary for the V3 solver. `icheme_column_solver` is a
shared target only: Windows produces a DLL, Linux an SO, and macOS a DYLIB. There is no native solver executable.

Configure and build it with:

```text
cmake -S src/main/cpp -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native --config Release
```

The Gradle verification lane builds and loads the library through JNI:

```text
./gradlew nativeSmoke
```

The lane passes `build/native` as `icheme.native.path` and runs one scalar Newton probe through the C ABI. A deployed
installation can set that system property to a shared-library file or directory.

The Java side loads `icheme_column_solver` through JNI when the library is installed and uses the existing Java path
when it is absent. The exported C ABI owns the Newton scratch buffers and is intentionally independent of JNI, so the
same core can later be called from Panama or another host without changing the numerical code. JNI also exposes an
optional residual callback for migration experiments and a batched classical Peng–Robinson outer-property call. The
batch call is enabled only with `-PnativeThermoBatch=true`; strict Java PR remains the publication and audit authority.
The full V3 MESH mapping remains on the Java side until its cross-language data contract is completed.
