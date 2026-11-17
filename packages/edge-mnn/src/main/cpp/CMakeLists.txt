cmake_minimum_required(VERSION 3.22.1)
project(engram_mnn_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(JNILIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
set(MNN_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/include")
set(MNN_CORE_SO "${JNILIBS_DIR}/libMNN.so")
set(MNN_EXPRESS_SO "${JNILIBS_DIR}/libMNN_Express.so")
set(MNN_LLM_SO "${JNILIBS_DIR}/libllm.so")
set(MNN_LLM_HEADER "${MNN_INCLUDE_DIR}/llm/llm.hpp")

add_library(engram_mnn_jni SHARED engram_mnn_jni.cpp)

# Android 15/16 16KB ELF page-size alignment
target_link_options(engram_mnn_jni PRIVATE "-Wl,-z,max-page-size=16384")

find_library(LOG_LIB log)

if(EXISTS "${MNN_CORE_SO}" AND EXISTS "${MNN_LLM_SO}" AND EXISTS "${MNN_LLM_HEADER}")
    message(STATUS "[Engram] Linking prebuilt MNN & MNN-LLM libraries from ${JNILIBS_DIR}")
    add_library(mnn_core SHARED IMPORTED)
    set_target_properties(mnn_core PROPERTIES IMPORTED_LOCATION "${MNN_CORE_SO}")

    add_library(mnn_llm SHARED IMPORTED)
    set_target_properties(mnn_llm PROPERTIES IMPORTED_LOCATION "${MNN_LLM_SO}")

    set(MNN_LINK_TARGETS mnn_llm mnn_core)
    if(EXISTS "${MNN_EXPRESS_SO}")
        add_library(mnn_express SHARED IMPORTED)
        set_target_properties(mnn_express PROPERTIES IMPORTED_LOCATION "${MNN_EXPRESS_SO}")
        list(APPEND MNN_LINK_TARGETS mnn_express)
    endif()

    target_compile_definitions(engram_mnn_jni PRIVATE ENGRAM_HAS_MNN_LIB=1)
    target_include_directories(engram_mnn_jni PRIVATE "${MNN_INCLUDE_DIR}")
    target_link_libraries(engram_mnn_jni PRIVATE ${MNN_LINK_TARGETS} ${LOG_LIB})
else()
    message(STATUS "[Engram] Prebuilt MNN libs not detected; compiling stub-free unavailable backend.")
    target_compile_definitions(engram_mnn_jni PRIVATE ENGRAM_HAS_MNN_LIB=0)
    target_link_libraries(engram_mnn_jni PRIVATE ${LOG_LIB})
endif()
