package br.com.cronapi.rest.security;

import org.junit.Test;

import cronapi.rest.security.CronappSecurity;

/**
 * Simples testes de usabilidade da anotação {@link CronappSecurity}
 *
 * @author arthemus
 * @since 01/08/17
 */
public class CronappSecurityTest {
  
  @CronappSecurity
  class PojoClassDefault {
    
  }
  
  @CronappSecurity(get = "Public")
  class PojoClassReadOnly {
    
  }
  
  @CronappSecurity(get = "Administrators;Financeiro", post = "Administrators", put = "Financeiro")
  class PojoClassManyRoles {
    
  }
  
  @Test
  public void testDefaultValues() {
    
  }
  
}
