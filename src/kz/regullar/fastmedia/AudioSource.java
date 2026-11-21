package kz.regullar.fastmedia;

public record AudioSource(int pid, String name, float peak, boolean active, boolean nowPlaying) {
}
