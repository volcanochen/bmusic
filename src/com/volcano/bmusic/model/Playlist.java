package com.volcano.bmusic.model;

public class Playlist {
    public long id;
    public String name;
    public long created;
    public int count;
    public String coverHint;

    public Playlist() {
    }

    public Playlist(long id, String name, long created) {
        this.id = id;
        this.name = name;
        this.created = created;
    }
}
