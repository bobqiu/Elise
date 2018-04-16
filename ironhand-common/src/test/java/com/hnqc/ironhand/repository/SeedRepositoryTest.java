package com.hnqc.ironhand.repository;

import com.hnqc.ironhand.common.CommonApplication;
import com.hnqc.ironhand.common.pojo.Seed;
import com.hnqc.ironhand.common.pojo.UrlEntry;
import com.hnqc.ironhand.common.repository.SeedRepository;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.ArrayList;
import java.util.List;

@RunWith(SpringRunner.class)
@DataJpaTest
@ContextConfiguration(classes = CommonApplication.class)
public class SeedRepositoryTest {
    @Autowired
    private SeedRepository repository;
    @Autowired
    private TestEntityManager entityManager;

    @Before
    public void prepareData() {
        Seed seed = new Seed();
        List<UrlEntry> html = new ArrayList<>();
        html.add(new UrlEntry("name", "value"));
        entityManager.persist(seed);
    }

    @Test
    public void testFind() {
        List<Seed> seeds = repository.findAll();
        Assert.assertEquals(1, seeds.size());
        Seed seed = seeds.get(0);
//        Assert.assertEquals(1, seed.getUrls().size());
    }
}
