ZOMDROID CP3 — JASSIMP DIRECT FIX INPUTS
========================================

Direct source
-------------

Base recipe:
  optlab_deps/zomdroid-dependencies-4da905a55889778a2a7a38f268e36b8bc595a8a5

Assimp source tag:
  v5.4.3

JNI/build patch:
  patches/assimp/0001.patch

PZ FBX compatibility patch:
  patches/assimp/0002.patch

The latter is byte-for-byte the patch introduced by commit
0dbe092850d5cf528dbdfac01603d9d1bb799d04 in
https://github.com/udarmolota/zomdroid-dependencies. It restores the
pre-Assimp-5.4 FBX bone offset semantics inside FBXConverter::ConvertCluster:

  bone->mOffsetMatrix = cluster->Transform();

That is the same Cluster/Transform matrix recovered by the supplied
ZomDroidAssimp53Compat agent and copied into JAssimp's m_offsetMatrix after
import. In the direct build, Assimp's normal post-process pipeline performs the
layout and MAKE_LEFT_HANDED conversion, so no Java post-import agent is needed.

Payloads
--------

payload/libjassimp64.so
  Direct ARM64 build from udarmolota/zomdroid commit
  1ac8acf10e9d92a4c1ae804b4681ec22a5f16bda. In that commit it was named
  libjassimp64.zomdroid.so; its ELF SONAME remains libjassimp64.so.

rollback/libjassimp64-r9-unpatched.so
  Exact R9 bundled Assimp 5.4.3 library before CP3.

reference/ZomDroidAssimp53Compat-0.1.0.jar
  Supplied behavior reference only. It is deliberately absent from jars.tar and
  from every runtime -javaagent argument.

Expected SHA-256
---------------

Direct payload:
  a73942ea3a4cdb25cd989661306151f35368af314e5e6d5b2fd20688f6609ac4

R9 rollback:
  095da5c4acb15cc43270c7f98bde1cf4f83e26abbc9c23bd3352fb97d7dbb849

Reference Java agent:
  af9f00fce0f264e75be6ac5642c63e10b2978aa618fb12ccb463885ae59a954a

Build environment used by the upstream dependency workflow
------------------------------------------------------------

Android NDK r27c (27.2.12479018), API 30, arm64-v8a, Release, JDK 17.
Use tools/build-jassimp-direct.sh for a clean rebuild and
tools/install-jassimp-direct.sh for an atomic bundle replacement.

Important upstream context
--------------------------

udarmolota/zomdroid later reverted automatic importer rerouting in commit
1afe3a525bbaff5bb0a223020b9367ae0216da3d because a separate mod-animation
failure was traced to lowercase aliases, not JAssimp. That does not remove the
PZ-specific 5.3 offset compatibility patch: the recipe remains in the dependency
repository. CP3 uses it for the independently demonstrated offset-matrix issue,
not for the disproven alias/animation theory.
