## Spring boot - Batch 예제
Spring 가이드 Batch Service를 공부하며 만든 프로젝트입니다.

### Batch Service
일괄처리 서비스는 한꺼번에 일괄적으로 대량 건을 처리하는 서비스이다.
스프링 부트에서는 배치작업을 위해 프레임워크를 제공한다. 스프링 부트 배치의 일반적인 시나리오는 
1. 읽기 (read)
2. 처리 (processing)
3. 쓰기 (write)

의 흐름을 가지며, Job과 Step을 이용하여 시나리오와 배치 작업을 생성한다.

* 참고자료
    * [예제 프로젝트](https://spring.io/guides/gs/batch-processing/#_put_together_a_batch_job)
    * [Spring Batch 간단정리 블로그 참조](https://cheese10yun.github.io/spring-batch-basic/)
    
***

### <h1>프로젝트 구조</h1>
.csv 파일을 읽어(read) 대문자로 치환(process) 후 DB에 삽입(write)하는 비즈니스 로직을 배치로 처리한다.

***

### 준비
resources에 읽어올 csv파일을 추가한다
```csv
Jill,Doe
Joe,Doe
Justin,Doe
Jane,Doe
John,Doe
```

DB에 삽입하기 위해 스키마를 정의한다
```mysql-sql
DROP TABLE people IF EXISTS;

CREATE TABLE people (
    person_id BIGINT IDENTITY NOT NULL PRIMARY KEY,
    first_name VARCHAR(20),
    last_name VARCHAR(20)
);
```

***

## Business Class
csv파일과 DB에 삽입하기 위한 정보를 담아낼 클래스를 생성한다.
```java
public class Person {

  private String lastName;
  private String firstName;

  /* Getter, Setter, Constructor*/
}
```

***

## Intermediate Processor
process단계에 수행할 로직을 작성한다. csv에서 읽어온 `Person`의 속성 `firstName`, `lastName`을 대문자로 바꾸어 `Person`객체로 반환하는 로직이다.

```java
public class PersonItemProcessor implements ItemProcessor<Person, Person> {

  private static final Logger log = LoggerFactory.getLogger(PersonItemProcessor.class);

  @Override
  public Person process(final Person person) throws Exception {
    final String firstName = person.getFirstName().toUpperCase();
    final String lastName = person.getLastName().toUpperCase();

    final Person transformedPerson = new Person(firstName, lastName);

    log.info("Converting (" + person + ") into (" + transformedPerson + ")");

    return transformedPerson;
  }

}
```

`PersonItemProcessor`는 Spring Batch에 속한 `ItemProcessor`인터페이스를 상속하였다. 이는 Batch job에서의 process를 쉽게 등록할 수 있도록 도와준다. Generic Type으로 전달한 `<Person, Person>`은 read 후 받아올 Type과 write에게 전달할 Type을 넣어준다.
이 프로젝트에서는 둘다 `Person`타입으로 읽어오고 전달해 줄것이기 때문에 같은 타입으로 넣어준것이지 달라도 문제가 되지 않는다.

***

### Batch Job

양이 많다... 먼저 부트에게 BatchProcessing에 필요한 설정을 하기위해 `@Configuration`과 `@EnableBatchProcessing`어노테이션을 붙인다.
@Autowired로 가져온 `JobBuilderFactory`, `StepBuilderFactory`는 Batch Job을 만들기 위해 필요한 의존이기 때문에 받아놓는다.

`reader`, `processor`, `writer`는 배치작업에 필요한 input, processor, output이며 빈으로 등록한다.

* `reader`: `ItemReader`를 생성한다. 이 프로젝트에서는 csv파일을 읽는 `FlatFileItemReader`를 생성한다.
* `processor`: `PersonItemProcessor`인스턴스를 생성한다.
* `writer`: `ItemWriter`를 생성한다. 이 프로젝트에서는 `JdbcBatchItemWriter`를 이용하여 DB에 write한다.

마지막 `Job`과 `Step`을 빈으로 등록한다. `Job`은 `step`들로 만들어지며 `step`은 `reader`, `processor`, `writer`을 가질수있다.
```java
@Configuration
@EnableBatchProcessing
public class BatchConfiguration {

    @Autowired
    public JobBuilderFactory jobBuilderFactory;

    @Autowired
    public StepBuilderFactory stepBuilderFactory;

    /**
     * input
     * */
    @Bean
    public FlatFileItemReader<Person> reader() {
        return new FlatFileItemReaderBuilder<Person>()
                .name("personItemReader")
                .resource(new ClassPathResource("sample-data.csv"))
                .delimited()
                .names("firstName", "lastName")
                .fieldSetMapper(new BeanWrapperFieldSetMapper<Person>() {{
                    setTargetType(Person.class);
                }})
                .build();
    }

    /**
     * processor
     * */
    @Bean
    public PersonItemProcessor processor() {
        return new PersonItemProcessor();
    }

    /**
     * output
     * */
    @Bean
    public JdbcBatchItemWriter<Person> writer(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<Person>()
                .itemSqlParameterSourceProvider(new BeanPropertyItemSqlParameterSourceProvider<>())
                .sql("INSERT INTO people (first_name, last_name) VALUES (:firstName, :lastName)")
                .dataSource(dataSource)
                .build();
    }

    @Bean
    public Job importUserJob(JobCompletionNotificationListener listener, Step step1) {
        return jobBuilderFactory.get("importUserJob")
                .incrementer(new RunIdIncrementer())
                .listener(listener)
                .flow(step1)
                .end()
                .build();
    }

    @Bean
    public Step step1(JdbcBatchItemWriter<Person> writer) {
        return stepBuilderFactory.get("step1")
                .<Person, Person> chunk(10)
                .reader(reader())
                .processor(processor())
                .writer(writer)
                .build();
    }


}
```

`step`에서 정의한 `chuck`는 아이템이 트랜잭션에 커밋되는 수를 말한다.
`read`한 데이터 수가 지정한 `chuck`의 단위와 일치하면 `write`를 수행하고 트랜잭션을 커밋한다.
이러한 방법으로 많은 데이터에 대해 프로세싱 중 생기는 실패에 대해 모든 데이터를 `rollback`하지 않아도 된다.
`chunk`단위로 커밋하였기 때문에 배치처리에 실패한 `chunk`와 다른 `chunk`는 영향을 받지 않는다.

***

### JobExecutionListener
`BatchConfiguration`에서 `Job`을 생성할때 Listener를 붙여주었다. 아래의 `JobCompletionNotificationListener`는 `Job`이 끝난 후 실행될 작업에 대해 정의한다.
생태가 `COMPLETED`상태이면 DB에 데이터가 잘 INSERT되었는지 확인한다.

```java
@Component
public class JobCompletionNotificationListener extends JobExecutionListenerSupport {

  private static final Logger log = LoggerFactory.getLogger(JobCompletionNotificationListener.class);

  private final JdbcTemplate jdbcTemplate;

  @Autowired
  public JobCompletionNotificationListener(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void afterJob(JobExecution jobExecution) {
    if(jobExecution.getStatus() == BatchStatus.COMPLETED) {
      log.info("!!! JOB FINISHED! Time to verify the results");

      jdbcTemplate.query("SELECT first_name, last_name FROM people",
        (rs, row) -> new Person(
          rs.getString(1),
          rs.getString(2))
      ).forEach(person -> log.info("Found <" + person + "> in the database."));
    }
  }
}
```

***

### Test
실행시 Batch Job은 단 한번 수행되고 종료된다. csv파일에 있는 데이터를 읽어와 대문자로 잘 변경됨을 확인할 수 있다.
```
... o.s.b.c.l.support.SimpleJobLauncher      : Job: [FlowJob: [name=importUserJob]] launched with the following parameters: [{run.id=1}]
... o.s.batch.core.job.SimpleStepHandler     : Executing step: [step1]
... c.e.batchprocessing.PersonItemProcessor  : Converting (firstName: Jill, lastName: Doe) into (firstName: DOE, lastName: JILL)
... c.e.batchprocessing.PersonItemProcessor  : Converting (firstName: Joe, lastName: Doe) into (firstName: DOE, lastName: JOE)
... c.e.batchprocessing.PersonItemProcessor  : Converting (firstName: Justin, lastName: Doe) into (firstName: DOE, lastName: JUSTIN)
... c.e.batchprocessing.PersonItemProcessor  : Converting (firstName: Jane, lastName: Doe) into (firstName: DOE, lastName: JANE)
... c.e.batchprocessing.PersonItemProcessor  : Converting (firstName: John, lastName: Doe) into (firstName: DOE, lastName: JOHN)
... o.s.batch.core.step.AbstractStep         : Step: [step1] executed in 96ms
... c.e.b.JobCompletionNotificationListener  : !!! JOB FINISHED! Time to verify the results
... c.e.b.JobCompletionNotificationListener  : Found <firstName: JILL, lastName: DOE> in the database.
... c.e.b.JobCompletionNotificationListener  : Found <firstName: JOE, lastName: DOE> in the database.
... c.e.b.JobCompletionNotificationListener  : Found <firstName: JUSTIN, lastName: DOE> in the database.
... c.e.b.JobCompletionNotificationListener  : Found <firstName: JANE, lastName: DOE> in the database.
... c.e.b.JobCompletionNotificationListener  : Found <firstName: JOHN, lastName: DOE> in the database.
... o.s.b.c.l.support.SimpleJobLauncher      : Job: [FlowJob: [name=importUserJob]] completed with the following parameters: [{run.id=1}] and the following status: [COMPLETED] in 146ms
```