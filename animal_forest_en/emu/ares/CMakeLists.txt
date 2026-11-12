# emu/ares/build.sh copies this directory into the ares tree as headless/ and adds it to the build.
add_executable(ares-headless headless.cpp)
# n64.hpp reaches paraLLEl-RDP's headers, which the core keeps private (ares/n64/CMakeLists.txt).
set(N64_DIR ${CMAKE_SOURCE_DIR}/ares/n64)
target_include_directories(
  ares-headless
  PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${CMAKE_SOURCE_DIR}/ares
    ${N64_DIR}/vulkan/parallel-rdp/parallel-rdp
    ${N64_DIR}/vulkan/parallel-rdp/volk
    ${N64_DIR}/vulkan/parallel-rdp/vulkan
    ${N64_DIR}/vulkan/parallel-rdp/vulkan-headers/include
    ${N64_DIR}/vulkan/parallel-rdp/util
)
target_compile_definitions(ares-headless PRIVATE MIA_LIBRARY)
target_link_libraries(ares-headless PRIVATE ares::ares ares::mia ares::nall)
