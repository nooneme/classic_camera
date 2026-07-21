cmake_minimum_required(VERSION 3.22)
project("classic_camera_lut")

add_library(lut_generator SHARED lut_generator.cpp)

add_library(tonecurve SHARED tone_curve.cc tone_curve_jni.cc)

target_compile_features(lut_generator PUBLIC cxx_std_17)
target_compile_features(tonecurve PUBLIC cxx_std_17)

target_include_directories(lut_generator PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_include_directories(tonecurve PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
