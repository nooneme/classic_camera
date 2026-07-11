cmake_minimum_required(VERSION 3.22)
project("classic_camera_lut")

add_library(lut_generator SHARED lut_generator.cpp)

add_library(align_merge SHARED align_merge.cpp)

target_compile_features(lut_generator PUBLIC cxx_std_17)
target_compile_features(align_merge PUBLIC cxx_std_17)

target_include_directories(lut_generator PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_include_directories(align_merge PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
