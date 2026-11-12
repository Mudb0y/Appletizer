// The flattened engine image (scripts/armv7-image.py), built into the library.
    .section .rodata
    .balign 16
    .global atzb_image
    .hidden atzb_image
atzb_image:
    .incbin IMAGE
    .global atzb_image_end
    .hidden atzb_image_end
atzb_image_end:
    .section .note.GNU-stack,"",%progbits
