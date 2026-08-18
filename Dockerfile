FROM eclipse-temurin:21-jdk

WORKDIR /app
COPY . .

RUN curl -L -o postgresql.jar https://jdbc.postgresql.org/download/postgresql-42.7.4.jar
RUN javac -cp .:postgresql.jar *.java

EXPOSE 4000
CMD ["java", "-cp", ".:postgresql.jar", "TreatzaServer"]
