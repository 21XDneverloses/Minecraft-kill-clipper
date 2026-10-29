package dev.killclip.core;

import java.util.Locale;

/** Runs the recorder below normal priority so the game always gets the CPU first. */
public final class ProcessPriority {
   private static final int BELOW_NORMAL_PRIORITY_CLASS = 0x4000;

   private ProcessPriority() {
   }

   public static void lower(Process process) {
      try {
         if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            Windows.lower((int)process.pid());
         } else {
            new ProcessBuilder("renice", "-n", "10", "-p", String.valueOf(process.pid()))
               .redirectOutput(ProcessBuilder.Redirect.DISCARD)
               .redirectError(ProcessBuilder.Redirect.DISCARD)
               .start();
         }
      } catch (Throwable ignored) {
         // Best effort: recording still works at normal priority.
      }
   }

   /** Separate class so JNA (shipped with Minecraft) is only loaded on Windows. */
   private static final class Windows {
      static void lower(int pid) {
         com.sun.jna.platform.win32.Kernel32 kernel = com.sun.jna.platform.win32.Kernel32.INSTANCE;
         com.sun.jna.platform.win32.WinNT.HANDLE handle = kernel.OpenProcess(com.sun.jna.platform.win32.WinNT.PROCESS_SET_INFORMATION, false, pid);
         if (handle != null) {
            kernel.SetPriorityClass(handle, new com.sun.jna.platform.win32.WinDef.DWORD(BELOW_NORMAL_PRIORITY_CLASS));
            kernel.CloseHandle(handle);
         }
      }
   }
}
