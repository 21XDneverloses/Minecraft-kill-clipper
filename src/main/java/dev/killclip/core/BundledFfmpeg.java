package dev.killclip.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Unpacks the FFmpeg build that ships inside the mod jar (resources under
 * ffmpeg/&lt;platform&gt;/, listed in files.txt) into a cache folder, once per
 * version, and returns the path of the executable.
 */
public final class BundledFfmpeg {
   private BundledFfmpeg() {
   }

   /** The bytedeco platform name for this machine, if a build exists for it. */
   public static Optional<String> platform(String osName, String osArch) {
      String os = osName.toLowerCase(Locale.ROOT);
      String arch = osArch.toLowerCase(Locale.ROOT);
      boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
      boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
      if (os.contains("win") && x64) {
         return Optional.of("windows-x86_64-gpl");
      } else if (os.contains("mac") && arm64) {
         return Optional.of("macosx-arm64-gpl");
      } else if (os.contains("mac") && x64) {
         return Optional.of("macosx-x86_64-gpl");
      } else if (os.contains("linux") && x64) {
         return Optional.of("linux-x86_64-gpl");
      } else {
         return Optional.empty();
      }
   }

   /**
    * Extracts the bundled build for {@code platform} under {@code cacheRoot}
    * and returns the executable, or empty when this jar has no build for it.
    */
   public static Optional<Path> extract(ClassLoader loader, String platform, Path cacheRoot) throws IOException {
      String base = "ffmpeg/" + platform + "/";
      List<String> listing = readListing(loader, base + "files.txt");
      if (listing.isEmpty() || !listing.get(0).startsWith("version=")) {
         return Optional.empty();
      }

      String version = listing.get(0).substring("version=".length());
      List<String> files = listing.subList(1, listing.size());
      String exe = files.contains("ffmpeg.exe") ? "ffmpeg.exe" : "ffmpeg";
      Path dir = cacheRoot.resolve(version + "-" + platform);
      if (Files.isRegularFile(dir.resolve(".complete")) && Files.isRegularFile(dir.resolve(exe))) {
         return Optional.of(dir.resolve(exe));
      }

      Files.createDirectories(cacheRoot);
      Path staging = Files.createTempDirectory(cacheRoot, "unpack-");
      try {
         for (String name : files) {
            if (name.contains("/") || name.contains("\\") || name.startsWith(".")) {
               throw new IOException("Bad file name in FFmpeg listing: " + name);
            }

            try (InputStream in = loader.getResourceAsStream(base + name)) {
               if (in == null) {
                  throw new IOException("Missing bundled FFmpeg file: " + name);
               }

               Files.copy(in, staging.resolve(name));
            }

            staging.resolve(name).toFile().setExecutable(true, true);
         }

         Files.writeString(staging.resolve(".complete"), version + System.lineSeparator());
         deleteTree(dir);
         try {
            Files.move(staging, dir, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException e) {
            Files.move(staging, dir);
         }
      } finally {
         deleteTree(staging);
      }

      return Optional.of(dir.resolve(exe));
   }

   private static List<String> readListing(ClassLoader loader, String resource) throws IOException {
      try (InputStream in = loader.getResourceAsStream(resource)) {
         if (in == null) {
            return List.of();
         }

         List<String> lines = new ArrayList<>();
         for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) {
               lines.add(line.strip());
            }
         }

         return lines;
      }
   }

   private static void deleteTree(Path root) throws IOException {
      if (!Files.exists(root)) {
         return;
      }

      try (Stream<Path> files = Files.walk(root)) {
         for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(file);
         }
      }
   }
}
