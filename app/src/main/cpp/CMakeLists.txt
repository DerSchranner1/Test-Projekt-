# Baut die Offline-Spracherkennung: whisper.cpp (MIT-Lizenz, liegt unter whisper/) plus die Brücke jni.c.
cmake_minimum_required(VERSION 3.22)
project(klarwhisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(W ${CMAKE_CURRENT_SOURCE_DIR}/whisper)

# Nur die CPU-Variante, statisch, ohne Zusatzbibliotheken
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
if (ANDROID)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
endif ()
add_subdirectory(${W}/ggml ggml)

add_library(klarwhisper SHARED ${W}/src/whisper.cpp jni.c)
target_include_directories(klarwhisper PRIVATE ${W}/include ${W}/src ${W}/ggml/include ${W}/ggml/src)
target_compile_definitions(klarwhisper PRIVATE GGML_USE_CPU WHISPER_VERSION="1.8.5")
target_compile_options(klarwhisper PRIVATE -O3 -fvisibility=hidden)

if (ANDROID)
    target_link_libraries(klarwhisper ggml log)
    target_link_options(klarwhisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
else ()
    # Für Tests auf dem Entwicklungsrechner (siehe README)
    find_package(JNI REQUIRED)
    target_include_directories(klarwhisper PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(klarwhisper ggml)
endif ()
