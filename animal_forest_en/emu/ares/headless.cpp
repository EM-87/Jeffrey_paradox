// A headless frontend for ares's Nintendo 64 core, driven one game frame at a time over stdin/stdout by
// emu/ares64.py: the second emulator the en ROM is played on (emu/n64emu.py drives mupen64plus).
//
//   ares-headless ROM [--counter ADDR:OFFSET] [--interpreter] [--hang VIS]
//
// The cartridge is identified by ares's own mia, as the ares app does it (Doubutsu no Mori by the ROM's ID:
// Flash RAM, the real-time clock, a Controller Pak), and its saves (.flash, .rtc, .pak) sit next to ROM, as
// the app keeps them. One gamepad in port 1. The N64 runs as the app runs it by default: the recompilers,
// paraLLEl-RDP on Vulkan, the VI's processing on, the Expansion Pak in.
//
// A game frame: ares runs one VI field per root->run(); with --counter, a frame is one step of the counter
// at *(u32*)ADDR + OFFSET (Animal Forest: gamePT->frameCounter), read through the CPU's data cache, so the
// script's frames are the game's, as mupen64plus's frame callback counts them.
//
// Commands, one per line; every answer is a line "ok LEN [fields]" (or "hang LEN fields", "err LEN text")
// followed by LEN bytes of payload:
//   frames N BUTTONS   run until N game frames have passed with the pad held at BUTTONS (mupen64plus's
//                      BUTTONS word: the stick in the high half); answers "ok 0 FRAME VI", or "hang 0 FRAME
//                      VI" after --hang VIs (default 900) without a game frame
//   vis N BUTTONS      run N VI fields; "ok 0 FRAME VI"
//   read ADDR LEN      LEN bytes of RDRAM at ADDR (KSEG0 or physical), through the data cache
//   write ADDR HEX     bytes into RDRAM through the data cache
//   screen             the last frame: width and height (u32, little-endian), then RGB
//   save PATH          the machine's state into PATH (ares's serializer)
//   load PATH          the machine's state from PATH
//   flush              the cartridge's saves (Flash RAM, the clock, the Controller Pak) written to disk
//   quit

#include <ares/ares.hpp>
#include <n64/n64.hpp>
#include <mia/mia.hpp>

#include <cstdio>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

namespace ares::Nintendo64 {
  auto load(Node::System& node, string name) -> bool;
  auto option(string name, string value) -> bool;
}

namespace {

// mupen64plus's BUTTONS word (m64p_plugin.h), as emu/n64emu.py presses it.
const std::map<std::string, u32> ButtonBits = {
  {"Right", 1u << 0}, {"Left", 1u << 1}, {"Down", 1u << 2}, {"Up", 1u << 3},
  {"Start", 1u << 4}, {"Z", 1u << 5}, {"B", 1u << 6}, {"A", 1u << 7},
  {"C-Right", 1u << 8}, {"C-Left", 1u << 9}, {"C-Down", 1u << 10}, {"C-Up", 1u << 11},
  {"R", 1u << 12}, {"L", 1u << 13},
};

// ares's gamepad scales an axis of -32767..32767 to its saturation radius, takes out an inner dead zone of 7
// and stretches the rest back (gamepad.cpp); this is the inverse, so a stick value v in -128..127 reaches the
// game as v (within the gate's 85).
s16 axisFor(int v) {
  if(v == 0) return 0;
  const double inner = 7.0, diagonal = 69.0;
  const double saturation = (inner + diagonal + sqrt(pow(inner + diagonal, 2.0) - 2.0 * sqrt(2.0) * diagonal * inner)) / sqrt(2.0);
  double magnitude = fabs((double)v);
  double position = (magnitude * (saturation - inner) / saturation + inner) * 32767.0 / saturation;
  if(position > 32767.0) position = 32767.0;
  return (s16)(v < 0 ? -position : position);
}

struct Headless : ares::Platform {
  ares::Node::System root;
  std::shared_ptr<mia::Pak> system, game, gamepad;
  u32 buttons = 0;
  std::vector<u8> rgb;
  u32 width = 0, height = 0;

  auto pak(ares::Node::Object node) -> std::shared_ptr<vfs::directory> override {
    if(node->name() == "Nintendo 64") return system->pak;
    if(node->name() == "Nintendo 64 Cartridge") return game->pak;
    if(node->name() == "Gamepad" && gamepad) return gamepad->pak;
    return {};
  }

  auto status(string_view message) -> void override {
    fprintf(stderr, "ares: %s\n", (const char*)message);
  }

  auto video(ares::Node::Video::Screen, const u32* data, u32 pitch, u32 w, u32 h) -> void override {
    width = w;
    height = h;
    rgb.resize((size_t)w * h * 3);
    u32 words = pitch / sizeof(u32);
    for(u32 y = 0; y < h; y++) {
      const u32* line = data + (size_t)y * words;
      u8* out = rgb.data() + (size_t)y * w * 3;
      for(u32 x = 0; x < w; x++) {
        u32 pixel = line[x];
        out[x * 3 + 0] = pixel >> 16;
        out[x * 3 + 1] = pixel >> 8;
        out[x * 3 + 2] = pixel;
      }
    }
  }

  auto audio(ares::Node::Audio::Stream stream) -> void override {
    f64 samples[8];
    while(stream->pending()) stream->read(samples);
  }

  auto input(ares::Node::Input::Input node) -> void override {
    std::string name = (const char*)node->name();
    if(auto button = node->cast<ares::Node::Input::Button>()) {
      auto bit = ButtonBits.find(name);
      button->setValue(bit != ButtonBits.end() && (buttons & bit->second));
    } else if(auto axis = node->cast<ares::Node::Input::Axis>()) {
      if(name == "X-Axis") axis->setValue(axisFor((s8)(buttons >> 16)));
      if(name == "Y-Axis") axis->setValue(-axisFor((s8)(buttons >> 24)));  // ares: down is +, N64: up is +
    }
  }
};

Headless headless;
u64 counterAddress = 0, counterOffset = 0;
u64 frame = 0, vi = 0;
u64 lastPointer = 0, lastCounter = 0;
bool counterSeen = false;
u32 hangVis = 900;

u8 peek(u32 address) {
  u32 physical = address & 0x1fff'ffff;
  return ares::Nintendo64::cpu.dcache.readDebug<ares::Nintendo64::Byte>(0x8000'0000 | physical, physical);
}

void poke(u32 address, u8 value) {
  u32 physical = address & 0x1fff'ffff;
  ares::Nintendo64::cpu.dcache.writeDebug<ares::Nintendo64::Byte>(0x8000'0000 | physical, physical, value);
}

u32 peek32(u32 address) {
  return peek(address) << 24 | peek(address + 1) << 16 | peek(address + 2) << 8 | peek(address + 3);
}

// One VI field; true when the game's frame counter stepped (or, without --counter, always).
u64 stepVi() {
  headless.root->run();
  vi++;
  if(!counterAddress) return 1;
  u32 pointer = peek32(counterAddress);
  if(pointer < 0x8000'0000 || pointer >= 0x8080'0000) return 0;
  u32 counter = peek32(pointer + counterOffset);
  u64 stepped = 0;
  if(counterSeen && pointer == lastPointer) {
    if(counter > lastCounter) stepped = counter - lastCounter;
    else if(counter < lastCounter) stepped = 1;
  } else if(counterSeen) {
    stepped = 1;  // a new game state: its counter starts again
  }
  counterSeen = true;
  lastPointer = pointer;
  lastCounter = counter;
  return stepped;
}

// The answers' channel: the process's stdout, kept apart from ares's own prints ("CPU JIT: flushing all
// blocks" and the like), which main() sends to stderr with the log.
FILE* protocol = nullptr;

void answer(const char* status, const void* payload, size_t length, const std::string& fields = "") {
  fprintf(protocol, "%s %zu%s%s\n", status, length, fields.empty() ? "" : " ", fields.c_str());
  if(length) fwrite(payload, 1, length, protocol);
  fflush(protocol);
}

std::string counters() {
  return std::to_string(frame) + " " + std::to_string(vi);
}

bool connectGamepad() {
  auto port = headless.root->find<ares::Node::Port>("Controller Port 1");
  if(!port) return false;
  auto peripheral = port->allocate("Gamepad");
  port->connect();
  if(auto pakPort = peripheral->find<ares::Node::Port>("Pak")) {
    if(headless.game->pak->attribute("port1/cpak").boolean()) {
      headless.gamepad = mia::Pak::create("Nintendo 64");
      headless.gamepad->pak->append("save.pak", 1984_KiB);
      headless.gamepad->load("save.pak", ".pak", headless.game->location);
      pakPort->allocate("Controller Pak");
      pakPort->connect();
    }
  }
  return true;
}

void flush() {
  headless.root->save();
  headless.system->save(headless.system->location);
  headless.game->save(headless.game->location);
  if(headless.gamepad) headless.gamepad->save("save.pak", ".pak", headless.game->location);
}

}  // namespace

int main(int argc, char** argv) {
  if(argc < 2) {
    fprintf(stderr, "usage: ares-headless ROM [--counter ADDR:OFFSET] [--interpreter] [--hang VIS]\n");
    return 2;
  }
  fflush(stdout);
  protocol = fdopen(dup(STDOUT_FILENO), "wb");
  dup2(STDERR_FILENO, STDOUT_FILENO);
  string romPath = argv[1];
  bool interpreter = false;
  for(int i = 2; i < argc; i++) {
    std::string arg = argv[i];
    if(arg == "--counter" && i + 1 < argc) {
      std::string spec = argv[++i];
      auto colon = spec.find(':');
      counterAddress = strtoull(spec.substr(0, colon).c_str(), nullptr, 0);
      counterOffset = colon == std::string::npos ? 0 : strtoull(spec.substr(colon + 1).c_str(), nullptr, 0);
    } else if(arg == "--interpreter") {
      interpreter = true;
    } else if(arg == "--hang" && i + 1 < argc) {
      hangVis = atoi(argv[++i]);
    } else {
      fprintf(stderr, "ares-headless: unknown argument %s\n", arg.c_str());
      return 2;
    }
  }

  ares::platform = &headless;
  headless.system = mia::System::create("Nintendo 64");
  if(headless.system->load() != successful) {
    fprintf(stderr, "ares-headless: the system's pak did not load\n");
    return 1;
  }
  headless.game = mia::Medium::create("Nintendo 64");
  if(headless.game->load(romPath) != successful) {
    fprintf(stderr, "ares-headless: %s did not load\n", (const char*)romPath);
    return 1;
  }
  fprintf(stderr, "ares-headless: %s, id %s, region %s, cic %s, cpak %d\n%s",
          (const char*)headless.game->pak->attribute("title"), (const char*)headless.game->pak->attribute("id"),
          (const char*)headless.game->pak->attribute("region"), (const char*)headless.game->pak->attribute("cic"),
          (int)headless.game->pak->attribute("port1/cpak").boolean(),
          (const char*)headless.game->manifest);

  // The ares app's defaults (desktop-ui/settings/settings.hpp).
  ares::Nintendo64::option("Quality", "SD");
  ares::Nintendo64::option("Supersampling", "false");
  ares::Nintendo64::option("Enable GPU acceleration", "true");
  ares::Nintendo64::option("Disable Video Interface Processing", "false");
  ares::Nintendo64::option("Weave Deinterlacing", "true");
  ares::Nintendo64::option("Homebrew Mode", "false");
  ares::Nintendo64::option("Recompiler", interpreter ? "false" : "true");
  ares::Nintendo64::option("Expansion Pak", "true");
  ares::Nintendo64::option("Controller Pak Banks", "32KiB (Default)");

  if(!ares::Nintendo64::load(headless.root, "[Nintendo] Nintendo 64 (NTSC)")) {
    fprintf(stderr, "ares-headless: the N64 did not load\n");
    return 1;
  }
  if(auto port = headless.root->find<ares::Node::Port>("Cartridge Slot")) {
    port->allocate();
    port->connect();
  }
  if(!connectGamepad()) {
    fprintf(stderr, "ares-headless: no controller port\n");
    return 1;
  }
  headless.root->power(false);
  answer("ok", nullptr, 0, "ready");

  std::string line;
  while(std::getline(std::cin, line)) {
    std::istringstream words(line);
    std::string command;
    words >> command;
    if(command == "frames" || command == "vis") {
      u64 count = 0;
      u32 pad = 0;
      words >> count >> pad;
      headless.buttons = pad;
      bool hung = false;
      if(command == "vis") {
        for(u64 i = 0; i < count; i++) frame += stepVi() ? 1 : 0;
      } else {
        u64 done = 0, idle = 0;
        while(done < count) {
          u64 stepped = stepVi();
          if(stepped) {
            done++;
            frame++;
            idle = 0;
          } else if(++idle >= hangVis) {
            hung = true;
            break;
          }
        }
      }
      answer(hung ? "hang" : "ok", nullptr, 0, counters());
    } else if(command == "read") {
      u64 address = 0, length = 0;
      words >> std::hex >> address >> std::dec >> length;
      std::vector<u8> bytes(length);
      for(u64 i = 0; i < length; i++) bytes[i] = peek(address + i);
      answer("ok", bytes.data(), bytes.size());
    } else if(command == "write") {
      u64 address = 0;
      std::string hex;
      words >> std::hex >> address >> hex;
      for(size_t i = 0; i + 1 < hex.size(); i += 2) poke(address + i / 2, strtoul(hex.substr(i, 2).c_str(), nullptr, 16));
      answer("ok", nullptr, 0);
    } else if(command == "screen") {
      std::vector<u8> out(8 + headless.rgb.size());
      memcpy(out.data(), &headless.width, 4);
      memcpy(out.data() + 4, &headless.height, 4);
      if(!headless.rgb.empty()) memcpy(out.data() + 8, headless.rgb.data(), headless.rgb.size());
      answer("ok", out.data(), out.size());
    } else if(command == "save") {
      std::string path;
      words >> path;
      auto state = headless.root->serialize(true);
      FILE* f = fopen(path.c_str(), "wb");
      if(!f || !state) {
        answer("err", "save", 4);
        if(f) fclose(f);
        continue;
      }
      fwrite(state.data(), 1, state.size(), f);
      fclose(f);
      answer("ok", nullptr, 0, std::to_string(state.size()));
    } else if(command == "load") {
      std::string path;
      words >> path;
      auto bytes = file::read(path.c_str());
      serializer state{bytes.data(), (u32)bytes.size()};
      if(bytes.empty() || !headless.root->unserialize(state)) {
        answer("err", "load", 4);
        continue;
      }
      counterSeen = false;
      answer("ok", nullptr, 0);
    } else if(command == "flush") {
      flush();
      answer("ok", nullptr, 0);
    } else if(command == "quit") {
      answer("ok", nullptr, 0);
      break;
    } else {
      answer("err", "command", 7);
    }
  }
  headless.root->unload();
  return 0;
}
