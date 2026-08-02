package com.bookmarkdrawer.folder;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "folder")
@Getter
@NoArgsConstructor
public class Folder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, unique = true, length = 64)
    private String name;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Folder(String name) {
        this.name = name;
        this.createdAt = LocalDateTime.now();
    }

    public void rename(String name) {
        this.name = name;
    }
}
