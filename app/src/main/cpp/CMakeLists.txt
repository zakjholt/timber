cmake_minimum_required(VERSION 3.22.1)
project(timber_audio)

add_library(timber_audio SHARED
    timber_audio_jni.cpp
    audio/AudioGraph.cpp
)

find_library(log-lib log)

target_include_directories(timber_audio PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/audio
)

target_link_libraries(timber_audio
    ${log-lib}
    aaudio
)
