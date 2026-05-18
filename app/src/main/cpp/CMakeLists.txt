cmake_minimum_required(VERSION 3.22.1)
project(receiver_playfair)

add_library(playfair STATIC
    playfair/playfair.c
    playfair/omg_hax.c
    playfair/modified_md5.c
    playfair/sap_hash.c
    playfair/hand_garble.c
)

target_include_directories(playfair PUBLIC playfair)

add_library(receiver_playfair SHARED receiver_playfair.c)
target_link_libraries(receiver_playfair playfair)
