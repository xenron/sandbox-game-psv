cmake_minimum_required(VERSION 2.8)

if(NOT DEFINED CMAKE_TOOLCHAIN_FILE)
  if(DEFINED ENV{VITASDK})
    set(CMAKE_TOOLCHAIN_FILE "$ENV{VITASDK}/share/vita.toolchain.cmake" CACHE PATH "toolchain file")
  else()
    message(FATAL_ERROR "Please define VITASDK to point to your SDK path!")
  endif()
endif()

project(installer)
include("${VITASDK}/share/vita.cmake" REQUIRED)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wall -O3 -Wno-unused-variable -Wno-unused-but-set-variable")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11 -fno-rtti -fno-exceptions")

include_directories(
)

link_directories(
  ${CMAKE_CURRENT_BINARY_DIR}
)

add_executable(installer
  compress.c
  crc32.c
  installer.c
)

target_link_libraries(installer
  gcc
  SceLibKernel_stub
  SceIofilemgr_stub
  SceKernelThreadMgr_stub
  SceSysmem_stub
  SceProcessmgr_stub
  SceDisplay_stub
  SceNet_stub
  SceNetCtl_stub
  SceHttp_stub
  SceShellSvc_stub
  SceCtrl_stub
  SceSysmodule_stub
  SceAppMgr_stub
  ScePromoterUtil_stub
)

set_target_properties(installer
  PROPERTIES LINK_FLAGS "-nostdlib"
)

vita_create_self(installer.self installer UNSAFE)
