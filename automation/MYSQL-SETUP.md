# HJ-001 시험용 MySQL 준비

이 절차는 기존 MySQL 서버에 한자리 전용 DB 두 개와 계정을 만든다. 기존 DB는 사용하지 않는다. 비밀번호는 채팅이나 Git 파일에 저장하지 않는다.

## 1. 관리자 접속

사용자 PowerShell에서 아래 명령을 실행하고 MySQL 관리자 비밀번호를 입력한다. Windows 로그인 비밀번호와 다를 수 있다.

```powershell
& 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' -u root -p
```

## 2. 이름 충돌 확인

MySQL 콘솔에서 실행한다. 결과가 있으면 기존 용도를 확인하고 다음 생성 명령은 진행하지 않는다.

```sql
SELECT SCHEMA_NAME FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME IN ('hanjari_dev', 'hanjari_test');
SELECT User, Host FROM mysql.user
WHERE User IN ('hanjari_dev', 'hanjari_test');
```

## 3. 전용 DB와 계정 생성

아래 두 비밀번호 자리표시자를 서로 다른 실제 비밀번호로 바꿔 MySQL 콘솔에 입력한다. 자리표시자를 그대로 쓰지 않는다. 암호에 작은따옴표나 역슬래시가 있다면 SQL 문자열 이스케이프가 필요하다.

```sql
CREATE DATABASE hanjari_dev CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE hanjari_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'hanjari_dev'@'localhost' IDENTIFIED BY '<개발용 비밀번호>';
CREATE USER 'hanjari_test'@'localhost' IDENTIFIED BY '<테스트용 비밀번호>';

GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
ON hanjari_dev.* TO 'hanjari_dev'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
ON hanjari_test.* TO 'hanjari_test'@'localhost';

SHOW GRANTS FOR 'hanjari_dev'@'localhost';
SHOW GRANTS FOR 'hanjari_test'@'localhost';
EXIT;
```

테스트 계정에 개발 DB 권한을 주지 않는다. Flyway clean이나 DB 삭제는 사용하지 않는다. Flyway 마이그레이션과 테스트 INSERT를 위해 해당 스키마 안에서만 권한을 부여한다.

## 4. 연결 확인

각 명령은 비밀번호를 별도로 물어본다. 비밀번호를 명령행 인수로 넣지 않는다.

```powershell
& 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' --protocol=TCP -h localhost -P 3306 -u hanjari_dev -p hanjari_dev -e 'SELECT DATABASE(), CURRENT_USER();'
& 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' --protocol=TCP -h localhost -P 3306 -u hanjari_test -p hanjari_test -e 'SELECT DATABASE(), CURRENT_USER();'
```

두 연결 성공 여부만 알려주면 된다. 앱 환경변수명과 실행 명령은 실제 구현의 README에서 확인한다. 연결 전에는 실제 MySQL 통합 테스트가 완료된 것이 아니다.
