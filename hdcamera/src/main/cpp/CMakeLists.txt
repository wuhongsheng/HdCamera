cmake_minimum_required(VERSION 3.4.1)

include_directories(${CMAKE_SOURCE_DIR}/librtmp)
message(CMAKE_SOURCE_DIR,${CMAKE_SOURCE_DIR})

# 加入子文件夹
add_subdirectory(librtmp)

#x264 lib
include_directories(${CMAKE_SOURCE_DIR}/x264/${ANDROID_ABI}/include)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -L${CMAKE_SOURCE_DIR}/x264/${ANDROID_ABI}/lib")

include_directories(${CMAKE_SOURCE_DIR}/faac/${ANDROID_ABI}/include)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -L${CMAKE_SOURCE_DIR}/faac/${ANDROID_ABI}/lib")

message(CMAKE_CXX_FLAGS,${CMAKE_CXX_FLAGS})


#此处可以通过多加几个addlibrary来针对多个cpp文件来生成多个.so文件
#它的用法跟Android.mk里的定义差不多
#需要留意，编译出来的文件放在\build\intermediates\cmake中,但是不用自己复制了，它会自动复制到
#jniLibs文件夹下
add_library(
        native-lib
        SHARED
        native-lib.cpp
        JavaCallHelper.cpp
        VideoChannel.cpp
        AudioChannel.cpp
)
#推流
target_link_libraries(
        native-lib
        rtmp
        x264
        faac
        log)


# 图像处理操作
include_directories(${CMAKE_SOURCE_DIR}/libyuv/include)
add_subdirectory(libyuv)


add_library(
        ImageUtils
        SHARED
        ImageUtils.cpp)

target_link_libraries(
        ImageUtils
        yuv)
