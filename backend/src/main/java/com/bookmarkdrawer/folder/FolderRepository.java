package com.bookmarkdrawer.folder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FolderRepository extends JpaRepository<Folder, Long> {

    boolean existsByName(String name);

    Optional<Folder> findByName(String name);

    List<Folder> findAllByOrderByNameAsc();
}
