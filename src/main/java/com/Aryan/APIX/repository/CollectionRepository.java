package com.Aryan.APIX.repository;

import com.Aryan.APIX.entity.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CollectionRepository extends JpaRepository<Collection,Long> {


    List<Collection> findByUser_Email(String email);
}
