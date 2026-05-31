TARGET   := circle_demo
PREFIX   := arm-none-eabi-
CC       := $(PREFIX)gcc
AS       := $(PREFIX)as
OBJCOPY  := $(PREFIX)objcopy

ARCH     := -mthumb-interwork -marm -mcpu=arm7tdmi -mtune=arm7tdmi
CFLAGS   := $(ARCH) -O2 -fno-builtin -nostdlib -nostartfiles \
             -fomit-frame-pointer -Wall
LDFLAGS  := -T gba.ld -nostdlib -nostartfiles

OBJS := gba_crt0.o main.o

.PHONY: all clean

all: $(TARGET).gba

$(TARGET).elf: $(OBJS)
	$(CC) $(LDFLAGS) -o $@ $^

$(TARGET).gba: $(TARGET).elf
	$(OBJCOPY) -O binary $< $@
	python3 fix_header.py $@ $@
	@echo "Built $(TARGET).gba — $(shell wc -c < $(TARGET).gba) bytes"

%.o: %.c
	$(CC) $(CFLAGS) -c -o $@ $<

%.o: %.s
	$(CC) $(ARCH) -c -o $@ $<

clean:
	rm -f $(OBJS) $(TARGET).elf $(TARGET).gba
