package com.example.batchprocessing;

public class Person {
    private String firstName;
    private String lastName;

    public Person() {}

    public Person(String lastName, String firstName) {
        this.firstName = firstName;
        this.lastName = lastName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    @Override
    public String toString() {
        return "firstName: " + firstName +", lastName: " + lastName;
    }
}
