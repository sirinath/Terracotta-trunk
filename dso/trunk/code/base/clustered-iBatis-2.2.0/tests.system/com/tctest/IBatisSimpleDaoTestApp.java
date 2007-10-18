package com.tctest;

import EDU.oswego.cs.dl.util.concurrent.CyclicBarrier;

import com.ibatis.common.resources.Resources;
import com.ibatis.dao.client.Dao;
import com.ibatis.dao.client.DaoException;
import com.ibatis.dao.client.DaoManager;
import com.ibatis.dao.client.DaoManagerBuilder;
import com.ibatis.dao.engine.transaction.ConnectionDaoTransaction;
import com.tc.object.config.ConfigVisitor;
import com.tc.object.config.DSOClientConfigHelper;
import com.tc.object.config.spec.CyclicBarrierSpec;
import com.tc.simulator.app.ApplicationConfig;
import com.tc.simulator.listener.ListenerProvider;
import com.tc.test.HSqlDBServer;
import com.tc.util.Assert;
import com.tc.util.TIMUtil;
import com.tctest.domain.Account;
import com.tctest.domain.AccountDAO;
import com.tctest.domain.Customer;
import com.tctest.domain.CustomerDAO;
import com.tctest.runner.AbstractTransparentApp;

import java.io.Reader;
import java.sql.Connection;
import java.sql.PreparedStatement;

public class IBatisSimpleDaoTestApp extends AbstractTransparentApp {
  private CyclicBarrier barrier;

  private AccountDAO    accountDAO, dao;

  private CustomerDAO   customerDAO;

  private Customer      cus;

  private HSqlDBServer  dbServer = null;

  public IBatisSimpleDaoTestApp(String appId, ApplicationConfig cfg, ListenerProvider listenerProvider) {
    super(appId, cfg, listenerProvider);
    barrier = new CyclicBarrier(getParticipantCount());
  }

  public void run() {
    try {
      int id = barrier.barrier();

      try {

        if (id == 0) {
          setupDatabase();

          synchronized (accountDAO) {
            Account acc = new Account();
            acc.setNumber("ASI-001");
            accountDAO.insertAccount(acc);
            Customer cus = new Customer();
            cus.setEmailAddress("asi@yahoo.com");
            cus.setFirstName("Antonio");
            cus.setLastName("Si");
            cus.setAccount(acc);
            customerDAO.insertCustomer(cus);
          }
        }

        barrier.barrier();

        if (id == 1) {
          synchronized (customerDAO) {
            cus = customerDAO.selectCustomer(0);
            Account acc = cus.getAccount();
            Assert.assertEquals("ASI-001", acc.getNumber());
          }
        }
        barrier.barrier();
        Assert.assertEquals("asi@yahoo.com", cus.getEmailAddress());
        Assert.assertEquals("Antonio", cus.getFirstName());
        Assert.assertEquals("Si", cus.getLastName());

        barrier.barrier();
      } finally {
        if (id == 0) {
          shutdownDatabase();
        }
      }

    } catch (Throwable e) {
      notifyError(e);
    }

  }

  private void setupDatabase() throws Exception {
    try {
      DaoManager daoManager = connectDatabase();
      dao = (AccountDAO) daoManager.getDao(AccountDAO.class);

      Connection conn = getConnection(daoManager, dao);
      PreparedStatement stmt = conn
          .prepareStatement("create table ACCOUNT (acc_id int not null, acc_number varchar(80) null, constraint pk_acc_id primary key (acc_id))");
      stmt.execute();

      stmt = conn
          .prepareStatement("create table CUSTOMER (cus_id int not null, cus_first_name varchar(80) null, cus_last_name varchar(80) null, cus_email varchar(80) null, cus_account_id varchar(80) null, constraint pk_cus_id primary key (cus_id))");
      stmt.execute();

      accountDAO = dao;
      customerDAO = (CustomerDAO) daoManager.getDao(CustomerDAO.class);
    } catch (Exception e) {
      e.printStackTrace(System.err);
      throw e;
    }
  }

  private DaoManager connectDatabase() throws Exception {
    dbServer = new HSqlDBServer();
    dbServer.start();
    Reader reader = Resources.getResourceAsReader("com/tctest/DAOMap.xml");
    DaoManager daoManager = DaoManagerBuilder.buildDaoManager(reader);
    reader.close();
    return daoManager;
  }

  private void shutdownDatabase() throws Exception {
    dbServer.stop();
  }

  private Connection getConnection(DaoManager daoManager, Dao dao) {
    com.ibatis.dao.client.DaoTransaction trans = daoManager.getTransaction(dao);
    if (!(trans instanceof ConnectionDaoTransaction)) throw new DaoException(
                                                                             "The DAO manager of type "
                                                                                 + daoManager.getClass().getName()
                                                                                 + " cannot supply a JDBC Connection for this template, and is therefore not"
                                                                                 + "supported by JdbcDaoTemplate.");
    else return ((ConnectionDaoTransaction) trans).getConnection();
  }

  public static void visitL1DSOConfig(ConfigVisitor visitor, DSOClientConfigHelper config) {
    String testClass = IBatisSimpleDaoTestApp.class.getName();

    config.getOrCreateSpec(testClass).addRoot("barrier", "barrier").addRoot("cus", "cus").addRoot("list", "list")
        .addRoot("customerDAO", "customerDAO").addRoot("accountDAO", "accountDAO");

    config.addWriteAutolock("* " + testClass + "*.*(..)");
    config.addIncludePattern("com.tctest.domain.*");
    new CyclicBarrierSpec().visit(visitor, config);

    config.addModule(TIMUtil.CGLIB_2_1_3, TIMUtil.getVersion(TIMUtil.CGLIB_2_1_3));
    config.addModule(TIMUtil.IBATIS_2_2_0, TIMUtil.getVersion(TIMUtil.IBATIS_2_2_0));
  }

}
